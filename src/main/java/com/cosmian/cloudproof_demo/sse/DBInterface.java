package com.cosmian.cloudproof_demo.sse;

import java.util.Map;
import java.util.Set;

import com.cosmian.CosmianException;
import com.cosmian.cloudproof_demo.sse.Sse.Key;
import com.cosmian.cloudproof_demo.sse.Sse.WordHash;

public interface DBInterface {

    /**
     * Retrieve the encrypted values of the Entry Table for a given set of word hashes
     * 
     * @param wordHashes as set of word hashes (sated by K₁)
     * @return the entries of word hashes to encrypted values
     * @throws CosmianException if the map cannot be fetched
     */
    Map<WordHash, DBEntryTableRecord> getEntryTableEntries(Set<WordHash> wordHashes) throws CosmianException;

    /**
     * Upsert the entries (Word hash -> encrypted value) in the Entry Table If there is a revision conflict, the
     * operation will be unsuccessful and false will be returned in the results map
     * 
     * @param entries the entries to upsert
     * @return a map of successful operations per WordHash
     * @throws CosmianException if the entries cannot be upserted
     */
    Map<WordHash, Boolean> upsertEntryTableEntries(Map<WordHash, DBEntryTableRecord> entries) throws CosmianException;

    /**
     * Retrieve the encrypted db UIDs from the Chain Table
     * 
     * @param chainTableKeys a list of chain table keys
     * @return the set of encrypted DB Uids
     * @throws CosmianException if the entries cannot be fetched
     */
    Set<byte[]> getChainTableEntries(Set<Key> chainTableKeys) throws CosmianException;

    /**
     * Upsert the entries (chain table keys -> encrypted DB uids) in the Entry Table
     * 
     * @param entries the entries to upsert
     * @throws CosmianException if the entries cannot be upserted
     */
    void upsertChainTableEntries(Map<Key, byte[]> entries) throws CosmianException;

}
