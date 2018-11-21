package com.iota.iri;

import com.sun.org.apache.bcel.internal.generic.BALOAD;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.*;

import org.apache.log4j.BasicConfigurator;   
import org.apache.log4j.Logger;   
import org.apache.log4j.PropertyConfigurator;   
import org.apache.log4j.xml.DOMConfigurator;
import com.iota.iri.model.*;
import sun.security.krb5.KdcComm;

//import org.apache.logging.log4j.Logger;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
import com.googlecode.jsonrpc4j.ProxyUtil;
import com.googlecode.jsonrpc4j.JsonRpcMultiServer;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import com.googlecode.jsonrpc4j.JsonRpcMethod;
import com.googlecode.jsonrpc4j.JsonRpcParam;
import com.googlecode.jsonrpc4j.StreamServer;
import com.googlecode.jsonrpc4j.JsonRpcClient;

import javax.net.ServerSocketFactory;
public class JosnRPCSocketClient{
        public void client(List<Block> params) throws Throwable {
            InetAddress bindAddress = InetAddress.getByName("127.0.0.1");
            Socket socket = new Socket(bindAddress, 1339);
            JsonRpcClient jsonRpcClient = new JsonRpcClient();
            Integer res = jsonRpcClient.invokeAndReadResponse("State.CommitBlock", params, Integer.class,
                    socket.getOutputStream(), socket.getInputStream());
            System.out.println(res);
          
        }

               
}