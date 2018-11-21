package com.iota.iri.model;

import java.util.*;
import java.io.IOException;

public class Block{
    public BlockBody Body;
    public Block(int index,List<ETransaction> transactions){
        Body=new BlockBody(index,transactions);
    }

}
