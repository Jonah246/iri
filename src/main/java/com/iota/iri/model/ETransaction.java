package com.iota.iri.model;

import java.util.*;
import java.io.IOException;

public class ETransaction{
    public int Gas;
    public int Gasprice;
    public int Value;
    public String From;
    public String To;
    public String Data;
    public boolean contractcreation;
    //nonce is not important,use shadow nonce
    public ETransaction(int gas,int gasprice,int value,String from,String to,String data,boolean contractcreation){
        this.Gas=gas;
        this.Gasprice=gasprice;
        this.Value=value;
        this.From=from;
        this.To=to;
        this.Data=data;
        this.contractcreation=contractcreation;
    }
}
