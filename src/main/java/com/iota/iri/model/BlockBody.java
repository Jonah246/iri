package com.iota.iri.model;


import java.util.*;
import java.io.IOException;

public class BlockBody{
    public int index;
    public List<ETransaction> transactions;
    public BlockBody(int index,List<ETransaction> transactions){
        this.index=index;
        this.transactions=transactions;
    }
}