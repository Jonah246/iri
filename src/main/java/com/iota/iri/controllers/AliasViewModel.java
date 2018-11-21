package com.iota.iri.controllers;

import com.iota.iri.model.Hash;
import com.iota.iri.model.IntegerIndex;
import com.iota.iri.model.Alias;
import com.iota.iri.storage.Indexable;
import com.iota.iri.storage.Persistable;
import com.iota.iri.storage.Tangle;
import com.iota.iri.utils.Pair;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Created by paul on 4/11/17.
 */
public class AliasViewModel {
    //correspend address persistence
    private final Alias alias;
    //obsoletetag name indexable
    private Hash hash;    
    
    public AliasViewModel(final Hash alias, final Hash hash) {
        this.alias=new Alias();
        this.alias.hash=alias;
        this.hash=hash;
    }
    AliasViewModel(final Alias alias, final Hash hash) {
        this.alias=alias;
        this.hash=hash;
    }
    public static AliasViewModel load(Tangle tangle, Hash hash) throws Exception {
        return new AliasViewModel((Alias) tangle.load(Alias.class, hash), hash);
    }
    public static boolean exists(Tangle tangle, Hash hash) throws Exception {
        return tangle.maybeHas(Alias.class, hash);
    }
    public boolean store(Tangle tangle) throws Exception {
        //return Tangle.instance().save(stateDiff, hash).get();
        if (hash.equals(Hash.NULL_HASH) || exists(tangle, hash)) {
            return false;
        }else{
        return tangle.save(alias, hash);
        }
    }
    public void delete(Tangle tangle) throws Exception {
        tangle.delete(Alias.class, hash);
    }
    public Hash getHash() {
        return hash;
    }
    public Hash getaddress(){
        return alias.hash;
    }
}
