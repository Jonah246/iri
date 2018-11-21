package com.iota.iri.model;

import com.iota.iri.storage.Persistable;


public class Vmstate implements Persistable{
    public static final int SIZE = 512;    
    public byte[] bytes;
    public byte[] bytes() {
        return bytes;
    }

    public void read(byte[] bytes) {
        if(bytes != null) {
            this.bytes = new byte[bytes.length];
            System.arraycopy(bytes, 0, this.bytes, 0, bytes.length);
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