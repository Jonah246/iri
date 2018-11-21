package com.iota.iri.model;

import com.iota.iri.storage.Persistable;


public class Alias implements Persistable{   
    public Hash hash;
    public byte[] bytes() {
        return  hash.bytes();
    }

    public void read(byte[] bytes) {
        if(bytes != null) {
            hash = new Hash(bytes);
        }
    }
    @Override
    public byte[] metadata() {
        return new byte[0];
    }

    @Override
    public void readMetadata(byte[] bytes) {

    }
    @Override
    public boolean merge() {
        return false;
    }
}