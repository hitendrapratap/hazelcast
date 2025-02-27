/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.map.impl.recordstore;


import com.hazelcast.concurrent.lock.LockService;
import com.hazelcast.concurrent.lock.LockStore;
import com.hazelcast.core.EntryView;
import com.hazelcast.core.ExecutionCallback;
import com.hazelcast.logging.ILogger;
import com.hazelcast.map.impl.EntryViews;
import com.hazelcast.map.impl.MapContainer;
import com.hazelcast.map.impl.MapEntries;
import com.hazelcast.map.impl.MapKeyLoader;
import com.hazelcast.map.impl.MapService;
import com.hazelcast.map.impl.MapServiceContext;
import com.hazelcast.map.impl.mapstore.MapDataStore;
import com.hazelcast.map.impl.record.Record;
import com.hazelcast.map.impl.record.Records;
import com.hazelcast.map.merge.MapMergePolicy;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.query.impl.Indexes;
import com.hazelcast.spi.DefaultObjectNamespace;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.spi.exception.RetryableHazelcastException;
import com.hazelcast.util.CollectionUtil;
import com.hazelcast.util.ExceptionUtil;
import com.hazelcast.util.FutureUtil;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;

import static com.hazelcast.map.impl.ExpirationTimeSetter.updateExpiryTime;
import static com.hazelcast.map.impl.mapstore.MapDataStores.EMPTY_MAP_DATA_STORE;
import static java.util.Collections.EMPTY_MAP;
import static java.util.Collections.EMPTY_SET;
import static java.util.Collections.emptyList;

/**
 * Default implementation of record-store.
 */
public class DefaultRecordStore extends AbstractEvictableRecordStore {

    protected final ILogger logger;
    protected final LockStore lockStore;
    protected final RecordStoreLoader recordStoreLoader;
    protected final MapKeyLoader keyLoader;
    // loadingFutures are modified by partition threads and could be accessed by query threads
    protected final Collection<Future> loadingFutures = new ConcurrentLinkedQueue<Future>();

    public DefaultRecordStore(MapContainer mapContainer, int partitionId,
                              MapKeyLoader keyLoader, ILogger logger) {
        super(mapContainer, partitionId);

        this.logger = logger;
        this.keyLoader = keyLoader;
        this.lockStore = createLockStore();
        this.recordStoreLoader = createRecordStoreLoader(mapStoreContext);
    }

    public void startLoading() {
        if (mapStoreContext.isMapLoader()) {
            loadingFutures.add(keyLoader.startInitialLoad(mapStoreContext, partitionId));
        }
    }

    @Override
    public boolean isLoaded() {
        return FutureUtil.allDone(loadingFutures);
    }

    @Override
    public void loadAll(boolean replaceExistingValues) {
        logger.info("Starting to load all keys for map " + name + " on partitionId=" + partitionId);
        Future<?> loadingKeysFuture = keyLoader.startLoading(mapStoreContext, replaceExistingValues);
        loadingFutures.add(loadingKeysFuture);
    }

    @Override
    public void loadAllFromStore(List<Data> keys, boolean replaceExistingValues) {
        if (!keys.isEmpty()) {
            Future f = recordStoreLoader.loadValues(keys, replaceExistingValues);
            loadingFutures.add(f);
        }

        keyLoader.trackLoading(false, null);
    }

    @Override
    public void updateLoadStatus(boolean lastBatch, Throwable exception) {
        keyLoader.trackLoading(lastBatch, exception);

        if (lastBatch) {
            logger.finest("Completed loading map " + name + " on partitionId=" + partitionId);
        }
    }

    @Override
    public void maybeDoInitialLoad() {
        if (keyLoader.shouldDoInitialLoad()) {
            loadAll(false);
        }
    }

    @Override
    public void destroy() {
        clearPartition();
        storage.destroy();
    }

    @Override
    public void onKeyLoad(ExecutionCallback<Boolean> callback) {
        keyLoader.onKeyLoad(callback);
    }

    @Override
    public void checkIfLoaded() {
        if (loadingFutures.isEmpty()) {
            return;
        }

        if (isLoaded()) {
            List<Future> doneFutures = null;
            try {
                doneFutures = FutureUtil.getAllDone(loadingFutures);
                // check all finished loading futures for exceptions
                FutureUtil.checkAllDone(doneFutures);
            } catch (Exception e) {
                logger.severe("Exception while loading map " + name, e);
                ExceptionUtil.rethrow(e);
            } finally {
                loadingFutures.removeAll(doneFutures);
            }
        } else {
            keyLoader.triggerLoadingWithDelay();
            throw new RetryableHazelcastException("Map " + getName()
                    + " is still loading data from external store");
        }
    }

    @Override
    public void flush() {
        final long now = getNow();
        final Collection<Data> processedKeys = mapDataStore.flush();
        for (Data key : processedKeys) {
            final Record record = getRecordOrNull(key, now, false);
            if (record != null) {
                record.onStore();
            }
        }
    }

    /**
     * Flushes evicted records to map store.
     *
     * @param recordsToBeFlushed records to be flushed to map-store.
     * @param backup             <code>true</code> if backup, false otherwise.
     */
    protected void flush(Collection<Record> recordsToBeFlushed, boolean backup) {
        Iterator<Record> iterator = recordsToBeFlushed.iterator();
        while (iterator.hasNext()) {
            Record record = iterator.next();
            mapDataStore.flush(record.getKey(), record.getValue(), backup);
        }
    }

    @Override
    public Record getRecord(Data key) {
        return storage.get(key);
    }

    @Override
    public void putRecord(Data key, Record record) {
        markRecordStoreExpirable(record.getTtl());
        storage.put(key, record);
    }

    @Override
    public Record putBackup(Data key, Object value) {
        return putBackup(key, value, DEFAULT_TTL, false);
    }

    @Override
    public Record putBackup(Data key, Object value, long ttl, boolean putTransient) {
        final long now = getNow();
        markRecordStoreExpirable(ttl);

        Record record = getRecordOrNull(key, now, true);
        if (record == null) {
            record = createRecord(value, ttl, now);
            storage.put(key, record);
        } else {
            updateRecord(key, record, value, now);
        }
        if (putTransient) {
            mapDataStore.addTransient(key, now);
        } else {
            mapDataStore.addBackup(key, value, now);
        }
        return record;
    }

    public Iterator<Record> iterator() {
        return new ReadOnlyRecordIterator(storage.values());
    }

    @Override
    public Iterator<Record> iterator(long now, boolean backup) {
        return new ReadOnlyRecordIterator(storage.values(), now, backup);
    }

    @Override
    public Iterator<Record> loadAwareIterator(long now, boolean backup) {
        checkIfLoaded();
        return iterator(now, backup);
    }

    @Override
    public void clearPartition() {
        NodeEngine nodeEngine = mapServiceContext.getNodeEngine();
        LockService lockService = nodeEngine.getSharedService(LockService.SERVICE_NAME);
        if (lockService != null) {
            final DefaultObjectNamespace namespace
                    = new DefaultObjectNamespace(MapService.SERVICE_NAME, name);
            lockService.clearLockStore(partitionId, namespace);
        }

        Indexes indexes = mapContainer.getIndexes();
        if (indexes.hasIndex()) {
            for (Record record : storage.values()) {
                Data key = record.getKey();
                Object value = Records.getValueOrCachedValue(record, serializationService);
                indexes.removeEntryIndex(key, value);
            }
        }
        resetAccessSequenceNumber();
        mapDataStore.clear();
        storage.clear();
    }

    /**
     * Size may not give precise size at a specific moment
     * due to the expiration logic. But eventually, it should be correct.
     *
     * @return record store size.
     */
    @Override
    public int size() {
        // do not add checkIfLoaded(), size() is also used internally
        return storage.size();
    }

    @Override
    public boolean isEmpty() {
        checkIfLoaded();
        return storage.isEmpty();
    }

    @Override
    public boolean containsValue(Object value) {
        checkIfLoaded();
        final long now = getNow();
        Collection<Record> records = storage.values();
        for (Record record : records) {
            if (getOrNullIfExpired(record, now, false) == null) {
                continue;
            }
            if (recordFactory.isEquals(value, record.getValue())) {
                return true;
            }
        }
        postReadCleanUp(now);
        return false;
    }

    @Override
    public boolean txnLock(Data key, String caller, long threadId, long referenceId, long ttl) {
        checkIfLoaded();
        return lockStore != null && lockStore.txnLock(key, caller, threadId, referenceId, ttl);
    }

    @Override
    public boolean extendLock(Data key, String caller, long threadId, long ttl) {
        checkIfLoaded();
        return lockStore != null && lockStore.extendLeaseTime(key, caller, threadId, ttl);
    }

    @Override
    public boolean unlock(Data key, String caller, long threadId, long referenceId) {
        checkIfLoaded();
        return lockStore != null && lockStore.unlock(key, caller, threadId, referenceId);
    }

    @Override
    public boolean forceUnlock(Data dataKey) {
        return lockStore != null && lockStore.forceUnlock(dataKey);
    }

    @Override
    public boolean isLocked(Data dataKey) {
        return lockStore != null && lockStore.isLocked(dataKey);
    }

    @Override
    public boolean isTransactionallyLocked(Data key) {
        return lockStore != null && lockStore.isTransactionallyLocked(key);
    }

    @Override
    public boolean canAcquireLock(Data key, String caller, long threadId) {
        return lockStore == null || lockStore.canAcquireLock(key, caller, threadId);
    }

    @Override
    public String getLockOwnerInfo(Data key) {
        return lockStore != null ? lockStore.getOwnerInfo(key) : null;
    }

    @Override
    public Set<Map.Entry<Data, Data>> entrySetData() {
        checkIfLoaded();
        long now = getNow();

        Collection<Record> records = storage.values();
        Map<Data, Data> tempMap = EMPTY_MAP;
        for (Record record : records) {
            Data key = record.getKey();
            record = getOrNullIfExpired(record, now, false);
            if (record == null) {
                continue;
            }
            if (tempMap == EMPTY_MAP) {
                tempMap = new HashMap<Data, Data>();
            }
            Data value = toData(record.getValue());
            tempMap.put(key, value);
        }
        return tempMap.entrySet();
    }

    @Override
    public Map.Entry<Data, Object> getMapEntry(Data key, long now) {
        checkIfLoaded();

        Record record = getRecordOrNull(key, now, false);
        if (record == null) {
            record = loadRecordOrNull(key, false);
        } else {
            accessRecord(record);
        }
        final Object value = record != null ? record.getValue() : null;
        return new AbstractMap.SimpleImmutableEntry<Data, Object>(key, value);
    }

    protected Record loadRecordOrNull(Data key, boolean backup) {
        Record record = null;
        final Object value = mapDataStore.load(key);
        if (value != null) {
            record = createRecord(value, DEFAULT_TTL, getNow());
            storage.put(key, record);
            if (!backup) {
                saveIndex(record, null);
            }
        }
        return record;
    }

    @Override
    public Set<Data> keySet() {
        checkIfLoaded();
        long now = getNow();

        Collection<Record> records = storage.values();
        Set<Data> keySet = EMPTY_SET;
        for (Record record : records) {
            Data key = record.getKey();
            record = getOrNullIfExpired(record, now, false);
            if (record == null) {
                continue;
            }
            if (keySet == EMPTY_SET) {
                keySet = new HashSet<Data>();
            }
            keySet.add(key);
        }

        return keySet;
    }

    @Override
    public int clear() {
        checkIfLoaded();

        // we don't remove locked keys. These are clearable records.
        Collection<Record> clearableRecords = getNotLockedRecords();
        // This conversion is required by mapDataStore#removeAll call.
        List<Data> keys = getKeysFromRecords(clearableRecords);
        mapDataStore.removeAll(keys);
        mapDataStore.clear();
        removeIndex(clearableRecords);
        resetAccessSequenceNumber();
        return removeRecords(clearableRecords);
    }

    protected List<Data> getKeysFromRecords(Collection<Record> clearableRecords) {
        List<Data> keys = new ArrayList<Data>(clearableRecords.size());
        for (Record clearableRecord : clearableRecords) {
            keys.add(clearableRecord.getKey());
        }
        return keys;
    }

    protected int removeRecords(Collection<Record> recordsToRemove) {
        if (CollectionUtil.isEmpty(recordsToRemove)) {
            return 0;
        }
        int removalSize = recordsToRemove.size();
        Iterator<Record> iterator = recordsToRemove.iterator();
        while (iterator.hasNext()) {
            Record record = iterator.next();
            storage.removeRecord(record);
            iterator.remove();
        }
        return removalSize;
    }

    protected Collection<Record> getNotLockedRecords() {
        Set<Data> lockedKeySet = lockStore == null ? null : lockStore.getLockedKeys();
        if (CollectionUtil.isEmpty(lockedKeySet)) {
            return storage.values();
        }

        int notLockedKeyCount = storage.size() - lockedKeySet.size();
        if (notLockedKeyCount <= 0) {
            return emptyList();
        }

        List<Record> notLockedRecords = new ArrayList<Record>(notLockedKeyCount);
        Collection<Record> records = storage.values();
        for (Record record : records) {
            if (!lockedKeySet.contains(record.getKey())) {
                notLockedRecords.add(record);
            }
        }
        return notLockedRecords;
    }

    /**
     * Resets the record store to it's initial state.
     */
    @Override
    public void reset() {
        resetAccessSequenceNumber();
        mapDataStore.clear();
        storage.clear();
    }

    @Override
    public Object evict(Data key, boolean backup) {
        Record record = storage.get(key);
        Object value = null;
        if (record != null) {
            value = record.getValue();
            mapDataStore.flush(key, value, backup);
            removeIndex(record);
            storage.removeRecord(record);
            if (!backup) {
                mapServiceContext.interceptRemove(name, value);
            }
        }
        return value;
    }

    @Override
    public int evictAll(boolean backup) {
        checkIfLoaded();

        Collection<Record> evictableRecords = getNotLockedRecords();
        flush(evictableRecords, backup);
        removeIndex(evictableRecords);
        resetAccessSequenceNumber();
        return removeRecords(evictableRecords);
    }

    @Override
    public void removeBackup(Data key) {
        final long now = getNow();

        final Record record = getRecordOrNull(key, now, true);
        if (record == null) {
            return;
        }
        storage.removeRecord(record);
        mapDataStore.removeBackup(key, now);
    }

    @Override
    public Object remove(Data key) {
        checkIfLoaded();
        final long now = getNow();

        final Record record = getRecordOrNull(key, now, false);
        Object oldValue;
        if (record == null) {
            oldValue = mapDataStore.load(key);
            if (oldValue != null) {
                mapDataStore.remove(key, now);
            }
        } else {
            oldValue = removeRecord(key, record, now);
        }
        return oldValue;
    }

    @Override
    public boolean remove(Data key, Object testValue) {
        checkIfLoaded();
        final long now = getNow();

        final Record record = getRecordOrNull(key, now, false);
        Object oldValue;
        boolean removed = false;
        if (record == null) {
            oldValue = mapDataStore.load(key);
            if (oldValue == null) {
                return false;
            }
        } else {
            oldValue = record.getValue();
        }
        if (recordFactory.isEquals(testValue, oldValue)) {
            mapServiceContext.interceptRemove(name, oldValue);
            removeIndex(record);
            mapDataStore.remove(key, now);
            onStore(record);
            storage.removeRecord(record);
            removed = true;
        }
        return removed;
    }

    @Override
    public boolean delete(Data key) {
        checkIfLoaded();
        final long now = getNow();

        final Record record = getRecordOrNull(key, now, false);
        if (record == null) {
            mapDataStore.remove(key, now);
        } else {
            return removeRecord(key, record, now) != null;
        }
        return false;
    }

    @Override
    public Object get(Data key, boolean backup) {
        checkIfLoaded();
        final long now = getNow();

        Record record = getRecordOrNull(key, now, backup);
        if (record == null) {
            record = loadRecordOrNull(key, backup);
        } else {
            accessRecord(record, now);
        }
        Object value = record == null ? null : record.getValue();
        value = mapServiceContext.interceptGet(name, value);

        postReadCleanUp(now);
        return value;
    }

    @Override
    public Data readBackupData(Data key) {
        final long now = getNow();

        final Record record = getRecord(key);

        if (record == null) {
            return null;
        }

        // expiration has delay on backups, but reading backup data should not be affected by this delay.
        // this is the reason why we are passing `false` to isExpired() method.
        final boolean expired = isExpired(record, now, false);
        if (expired) {
            return null;
        }
        final MapServiceContext mapServiceContext = this.mapServiceContext;
        final Object value = record.getValue();
        mapServiceContext.interceptAfterGet(name, value);
        // this serialization step is needed not to expose the object, see issue 1292
        return mapServiceContext.toData(value);
    }

    @Override
    public MapEntries getAll(Set<Data> keys) {
        checkIfLoaded();
        final long now = getNow();

        final MapEntries mapEntries = new MapEntries();

        final Iterator<Data> iterator = keys.iterator();
        while (iterator.hasNext()) {
            final Data key = iterator.next();
            final Record record = getRecordOrNull(key, now, false);
            if (record != null) {
                addMapEntrySet(key, record.getValue(), mapEntries);
                accessRecord(record);
                iterator.remove();
            }
        }

        Map loadedEntries = loadEntries(keys);
        addMapEntrySet(loadedEntries, mapEntries);

        return mapEntries;
    }

    protected Map loadEntries(Set<Data> keys) {
        Map loadedEntries = mapDataStore.loadAll(keys);
        if (loadedEntries == null || loadedEntries.isEmpty()) {
            return Collections.emptyMap();
        }
        // add loaded key-value pairs to this record-store.
        Set entrySet = loadedEntries.entrySet();
        for (Object object : entrySet) {
            Map.Entry entry = (Map.Entry) object;
            putFromLoad(toData(entry.getKey()), entry.getValue());
        }
        return loadedEntries;
    }

    protected void addMapEntrySet(Object key, Object value, MapEntries mapEntries) {
        if (key == null || value == null) {
            return;
        }
        value = mapServiceContext.interceptGet(name, value);
        final Data dataKey = mapServiceContext.toData(key);
        final Data dataValue = mapServiceContext.toData(value);
        mapEntries.add(dataKey, dataValue);
    }

    protected void addMapEntrySet(Map<Object, Object> entries, MapEntries mapEntries) {
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            addMapEntrySet(entry.getKey(), entry.getValue(), mapEntries);
        }
    }


    @Override
    public boolean containsKey(Data key) {
        checkIfLoaded();
        final long now = getNow();

        Record record = getRecordOrNull(key, now, false);
        if (record == null) {
            Object value = mapDataStore.load(key);
            if (value != null) {
                record = createRecord(value, DEFAULT_TTL, now);
                storage.put(key, record);
            }
        }
        boolean contains = record != null;
        if (contains) {
            accessRecord(record, now);
        }

        postReadCleanUp(now);
        return contains;
    }

    @Override
    public Object put(Data key, Object value, long ttl) {
        return putInternal(key, value, ttl, true);
    }

    protected Object putInternal(Data key, Object value, long ttl, boolean loadFromStore) {
        checkIfLoaded();

        long now = getNow();
        markRecordStoreExpirable(ttl);

        Record record = getRecordOrNull(key, now, false);
        Object oldValue = record == null ? (loadFromStore ? mapDataStore.load(key) : null) : record.getValue();
        value = mapServiceContext.interceptPut(name, oldValue, value);
        value = mapDataStore.add(key, value, now);
        onStore(record);

        if (record == null) {
            record = createRecord(value, ttl, now);
            storage.put(key, record);
        } else {
            updateRecord(key, record, value, now);
            updateExpiryTime(record, ttl, mapContainer.getMapConfig());
        }

        saveIndex(record, oldValue);
        return oldValue;
    }

    @Override
    public boolean merge(Data key, EntryView mergingEntry, MapMergePolicy mergePolicy) {
        checkIfLoaded();
        final long now = getNow();

        Record record = getRecordOrNull(key, now, false);
        mergingEntry = EntryViews.convertToLazyEntryView(mergingEntry, serializationService, mergePolicy);
        Object newValue;
        Object oldValue = null;
        if (record == null) {
            final Object notExistingKey = mapServiceContext.toObject(key);
            final EntryView nullEntryView = EntryViews.createNullEntryView(notExistingKey);
            newValue = mergePolicy.merge(name, mergingEntry, nullEntryView);
            if (newValue == null) {
                return false;
            }
            newValue = mapDataStore.add(key, newValue, now);
            record = createRecord(newValue, DEFAULT_TTL, now);
            mergeRecordExpiration(record, mergingEntry);
            storage.put(key, record);
        } else {
            oldValue = record.getValue();
            EntryView existingEntry = EntryViews.createLazyEntryView(record.getKey(), record.getValue(),
                    record, serializationService, mergePolicy);
            newValue = mergePolicy.merge(name, mergingEntry, existingEntry);
            // existing entry will be removed
            if (newValue == null) {
                removeIndex(record);
                mapDataStore.remove(key, now);
                onStore(record);
                storage.removeRecord(record);
                return true;
            }
            if (newValue == mergingEntry.getValue()) {
                mergeRecordExpiration(record, mergingEntry);
            }
            // same with the existing entry so no need to map-store etc operations.
            if (recordFactory.isEquals(newValue, oldValue)) {
                return true;
            }
            newValue = mapDataStore.add(key, newValue, now);
            onStore(record);
            storage.updateRecordValue(key, record, newValue);
        }
        saveIndex(record, oldValue);
        return newValue != null;
    }

    // TODO why does not replace method load data from map store if currently not available in memory.
    @Override
    public Object replace(Data key, Object update) {
        checkIfLoaded();
        final long now = getNow();

        final Record record = getRecordOrNull(key, now, false);
        if (record == null || record.getValue() == null) {
            return null;
        }
        Object oldValue = record.getValue();
        update = mapServiceContext.interceptPut(name, oldValue, update);
        update = mapDataStore.add(key, update, now);
        onStore(record);
        updateRecord(key, record, update, now);
        saveIndex(record, oldValue);
        return oldValue;
    }

    @Override
    public boolean replace(Data key, Object expect, Object update) {
        checkIfLoaded();
        final long now = getNow();

        final Record record = getRecordOrNull(key, now, false);
        if (record == null) {
            return false;
        }
        final MapServiceContext mapServiceContext = this.mapServiceContext;
        final Object current = record.getValue();
        final String mapName = this.name;
        if (!recordFactory.isEquals(expect, current)) {
            return false;
        }
        update = mapServiceContext.interceptPut(mapName, current, update);
        update = mapDataStore.add(key, update, now);
        onStore(record);
        updateRecord(key, record, update, now);
        saveIndex(record, current);
        return true;
    }

    @Override
    public void putTransient(Data key, Object value, long ttl) {
        checkIfLoaded();
        final long now = getNow();
        markRecordStoreExpirable(ttl);

        Record record = getRecordOrNull(key, now, false);
        Object oldValue = null;
        if (record == null) {
            value = mapServiceContext.interceptPut(name, null, value);
            record = createRecord(value, ttl, now);
            storage.put(key, record);
        } else {
            oldValue = record.getValue();
            value = mapServiceContext.interceptPut(name, oldValue, value);
            updateRecord(key, record, value, now);
            updateExpiryTime(record, ttl, mapContainer.getMapConfig());
        }
        saveIndex(record, oldValue);
        mapDataStore.addTransient(key, now);
    }

    @Override
    public Object putFromLoad(Data key, Object value) {
        return putFromLoad(key, value, DEFAULT_TTL);
    }

    @Override
    public Object putFromLoad(Data key, Object value, long ttl) {
        final long now = getNow();

        if (shouldEvict(now)) {
            return null;
        }
        markRecordStoreExpirable(ttl);

        Record record = getRecordOrNull(key, now, false);
        Object oldValue = null;
        if (record == null) {
            value = mapServiceContext.interceptPut(name, null, value);
            record = createRecord(value, ttl, now);
            storage.put(key, record);
        } else {
            oldValue = record.getValue();
            value = mapServiceContext.interceptPut(name, oldValue, value);
            updateRecord(key, record, value, now);
            updateExpiryTime(record, ttl, mapContainer.getMapConfig());
        }
        saveIndex(record, oldValue);
        return oldValue;
    }

    @Override
    public boolean set(Data dataKey, Object value, long ttl) {
        Object oldValue = putInternal(dataKey, value, ttl, false);
        return oldValue == null;
    }

    @Override
    public Object putIfAbsent(Data key, Object value, long ttl) {
        checkIfLoaded();
        final long now = getNow();
        markRecordStoreExpirable(ttl);

        Record record = getRecordOrNull(key, now, false);
        Object oldValue;
        if (record == null) {
            oldValue = mapDataStore.load(key);
            if (oldValue != null) {
                record = createRecord(oldValue, DEFAULT_TTL, now);
                storage.put(key, record);
            }
        } else {
            accessRecord(record, now);
            oldValue = record.getValue();
        }
        if (oldValue == null) {
            value = mapServiceContext.interceptPut(name, null, value);
            value = mapDataStore.add(key, value, now);
            onStore(record);
            record = createRecord(value, ttl, now);
            storage.put(key, record);
            updateExpiryTime(record, ttl, mapContainer.getMapConfig());
        }
        saveIndex(record, oldValue);
        return oldValue;
    }


    @Override
    public MapDataStore<Data, Object> getMapDataStore() {
        return mapDataStore;
    }

    protected Object removeRecord(Data key, Record record, long now) {
        Object oldValue = record.getValue();
        oldValue = mapServiceContext.interceptRemove(name, oldValue);
        if (oldValue != null) {
            removeIndex(record);
            mapDataStore.remove(key, now);
            onStore(record);
        }
        storage.removeRecord(record);
        return oldValue;
    }

    @Override
    public Record getRecordOrNull(Data key) {
        final long now = getNow();

        return getRecordOrNull(key, now, false);
    }

    protected Record getRecordOrNull(Data key, long now, boolean backup) {
        Record record = storage.get(key);
        if (record == null) {
            return null;
        }
        record = getOrNullIfExpired(record, now, backup);
        if (record != null && record.getValue() == null) {
            record = null;
        }
        return record;
    }

    protected void onStore(Record record) {
        if (record == null || mapDataStore == EMPTY_MAP_DATA_STORE) {
            return;
        }

        record.onStore();
    }
}
