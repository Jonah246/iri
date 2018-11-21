package com.iota.iri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import com.sun.org.apache.bcel.internal.generic.BALOAD;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import jota.IotaAPI;
import jota.dto.response.GetAttachToTangleResponse;
import jota.dto.response.GetNodeInfoResponse;
import jota.dto.response.GetTransactionsToApproveResponse;
import jota.model.Bundle;
import jota.model.Transaction;

import org.apache.commons.codec.binary.Hex;

import org.json.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import com.iota.iri.storage.*;
import com.iota.iri.model.*;
import java.io.File;

import com.iota.iri.controllers.*;
import com.iota.iri.hash.SpongeFactory;
import com.iota.iri.zmq.MessageQ;
import com.iota.iri.storage.Tangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.log4j.BasicConfigurator;     
import org.apache.log4j.PropertyConfigurator;   
import org.apache.log4j.xml.DOMConfigurator;

import sun.security.krb5.KdcComm;


import com.iota.iri.hash.ISS;
import com.iota.iri.model.Hash;
import com.iota.iri.utils.*;
import static com.iota.iri.Milestone.Validity.*;

import com.googlecode.jsonrpc4j.ProxyUtil;
import com.googlecode.jsonrpc4j.JsonRpcMultiServer;
import com.googlecode.jsonrpc4j.JsonRpcServer;
import com.googlecode.jsonrpc4j.JsonRpcMethod;
import com.googlecode.jsonrpc4j.JsonRpcParam;
import com.googlecode.jsonrpc4j.StreamServer;
import com.googlecode.jsonrpc4j.JsonRpcClient;


public class Milestone {

    enum Validity {
        VALID,
        INVALID,
        INCOMPLETE
    }

    private final Logger log = LoggerFactory.getLogger(Milestone.class);
    private final Tangle tangle;
    private final Hash coordinator;
    private final TransactionValidator transactionValidator;
    private final boolean testnet;
    private final MessageQ messageQ;
    public Snapshot latestSnapshot;

    private LedgerValidator ledgerValidator;
    private final static int TRYTES_SIZE = 2673;
    private final static long MAX_TIMESTAMP_VALUE = (3^27 - 1) / 2;
    public Hash latestMilestone = Hash.NULL_HASH;
    public Hash latestSolidSubtangleMilestone = latestMilestone;

    public static final int MILESTONE_START_INDEX = 338000;
    private static final int NUMBER_OF_KEYS_IN_A_MILESTONE = 20;

    public int latestMilestoneIndex = MILESTONE_START_INDEX;
    public int latestSolidSubtangleMilestoneIndex = MILESTONE_START_INDEX;

    private final Set<Hash> analyzedMilestoneCandidates = new HashSet<>();

    public Milestone(final Tangle tangle,
                     final Hash coordinator,
                     final Snapshot initialSnapshot,
                     final TransactionValidator transactionValidator,
                     final boolean testnet,
                     final MessageQ messageQ
                     ) {
        this.tangle = tangle;
        this.coordinator = coordinator;
        this.latestSnapshot = initialSnapshot;
        this.transactionValidator = transactionValidator;
        this.testnet = testnet;
        this.messageQ = messageQ;
    }

    private boolean shuttingDown;
    private static int RESCAN_INTERVAL = 5000;

    public void init(final SpongeFactory.Mode mode, final LedgerValidator ledgerValidator, final boolean revalidate) throws Exception {
        this.ledgerValidator = ledgerValidator;
        AtomicBoolean ledgerValidatorInitialized = new AtomicBoolean(false);
        (new Thread(() -> {
            while(!ledgerValidatorInitialized.get()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }
            }
            while (!shuttingDown) {
                long scanTime = System.currentTimeMillis();

                try {
                    final int previousLatestMilestoneIndex = latestMilestoneIndex;
                    Set<Hash> hashes = AddressViewModel.load(tangle, coordinator).getHashes();
                    { // Update Milestone
                        { // find new milestones
                            for(Hash hash: hashes) {
                                if(analyzedMilestoneCandidates.add(hash)) {
                                    TransactionViewModel t = TransactionViewModel.fromHash(tangle, hash);
                                    if (t.getCurrentIndex() == 0) {
                                        final Validity valid = validateMilestone(mode, t, getIndex(t));
                                        switch (valid) {
                                            case VALID:
                                                MilestoneViewModel milestoneViewModel = MilestoneViewModel.latest(tangle);
                                                if (milestoneViewModel != null && milestoneViewModel.index() > latestMilestoneIndex) {
                                                    latestMilestone = milestoneViewModel.getHash();
                                                    latestMilestoneIndex = milestoneViewModel.index();
                                                }
                                                break;
                                            case INCOMPLETE:
                                                analyzedMilestoneCandidates.remove(t.getHash());
                                                break;
                                            case INVALID:
                                                //Do nothing
                                                break;
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (previousLatestMilestoneIndex != latestMilestoneIndex) {

                        messageQ.publish("lmi %d %d", previousLatestMilestoneIndex, latestMilestoneIndex);
                        log.info("Latest milestone has changed from #" + previousLatestMilestoneIndex
                                + " to #" + latestMilestoneIndex);
                    }

                    Thread.sleep(Math.max(1, RESCAN_INTERVAL - (System.currentTimeMillis() - scanTime)));

                } catch (final Exception e) {
                    log.error("Error during Latest Milestone updating", e);
                }
            }
        }, "Latest Milestone Tracker")).start();

        (new Thread(() -> {
            try {
                ledgerValidator.init();
                ledgerValidatorInitialized.set(true);
            } catch (Exception e) {
                log.error("Error initializing snapshots. Skipping.", e);
            }
            while (!shuttingDown) {
                long scanTime = System.currentTimeMillis();

                try {
                    final int previousSolidSubtangleLatestMilestoneIndex = latestSolidSubtangleMilestoneIndex;

                    if(latestSolidSubtangleMilestoneIndex < latestMilestoneIndex) {
                       /* Hash testaddress=new Hash("MKIKIV9GPDADO9JLJWLUPQHNIQWWTBMLJBYZNPMVAVWI9YXULYBPTDWVFFDSLKDYSNFUIUWTXYYDIPPDX");
                        long startTime=System.currentTimeMillis();
                        for(int i=0;i<10000;i++)
                            {
                                    Hash testalias=new Hash(randomString(81));
                                    log.info("alias is  {} :",testalias);
                                    AliasViewModel aliasviewmodel=new AliasViewModel(testalias,testaddress);
                                    aliasviewmodel.store(tangle);
                            }
                        long endTime=System.currentTimeMillis();   
                        System.out.println("cost time： "+(endTime-startTime)+"ms");
                            */
                        updateLatestSolidSubtangleMilestone();
                        //String address1="QYHGU99OYMURAHXQAAEQRQHNDW9NXHIJHLHONPFJA9NVY99XZJPQXBU9XEATM9XAYFUZKPPMVHMNYXTA9";
                        //String address2="NJTC9EETDMNS9DVKOIS9KDONYZGSZXCDPILH9AQJJPTJJW9USYPPNCHRKUULEMNDOBELUHCNXSONCFVIA";
                        //newinternaltx(10,address1,address1);
                        try{
                            reordertransaction(previousSolidSubtangleLatestMilestoneIndex);
                        }catch(Throwable e){
                            log.error(" error {} ", e );
                        }
                    }

                    if (previousSolidSubtangleLatestMilestoneIndex != latestSolidSubtangleMilestoneIndex) {

                        messageQ.publish("lmsi %d %d", previousSolidSubtangleLatestMilestoneIndex, latestSolidSubtangleMilestoneIndex);
                        messageQ.publish("lmhs %s", latestSolidSubtangleMilestone);
                        log.info("Latest SOLID SUBTANGLE milestone has changed from #"
                                + previousSolidSubtangleLatestMilestoneIndex + " to #"
                                + latestSolidSubtangleMilestoneIndex);
                    }

                    Thread.sleep(Math.max(1, RESCAN_INTERVAL - (System.currentTimeMillis() - scanTime)));

                } catch (final Exception e) {
                    log.error("Error during Solid Milestone updating", e);
                }
            }
        }, "Solid Milestone Tracker")).start();


    }
    
    private Validity validateMilestone(SpongeFactory.Mode mode, TransactionViewModel transactionViewModel, int index) throws Exception {
        if (index < 0 || index >= 0x200000) {
            return INVALID;
        }

        if (MilestoneViewModel.get(tangle, index) != null) {
            // Already validated.
            return VALID;
        }
        final List<List<TransactionViewModel>> bundleTransactions = BundleValidator.validate(tangle, transactionViewModel.getHash());
        if (bundleTransactions.size() == 0) {
            return INCOMPLETE;
        }
        else {
            for (final List<TransactionViewModel> bundleTransactionViewModels : bundleTransactions) {

                //if (Arrays.equals(bundleTransactionViewModels.get(0).getHash(),transactionViewModel.getHash())) {
                if (bundleTransactionViewModels.get(0).getHash().equals(transactionViewModel.getHash())) {

                    //final TransactionViewModel transactionViewModel2 = StorageTransactions.instance().loadTransaction(transactionViewModel.trunkTransactionPointer);
                    final TransactionViewModel transactionViewModel2 = transactionViewModel.getTrunkTransaction(tangle);
                    if (transactionViewModel2.getType() == TransactionViewModel.FILLED_SLOT
                            && transactionViewModel.getBranchTransactionHash().equals(transactionViewModel2.getTrunkTransactionHash())
                            && transactionViewModel.getBundleHash().equals(transactionViewModel2.getBundleHash())) {

                        final int[] trunkTransactionTrits = transactionViewModel.getTrunkTransactionHash().trits();
                        final int[] signatureFragmentTrits = Arrays.copyOfRange(transactionViewModel.trits(), TransactionViewModel.SIGNATURE_MESSAGE_FRAGMENT_TRINARY_OFFSET, TransactionViewModel.SIGNATURE_MESSAGE_FRAGMENT_TRINARY_OFFSET + TransactionViewModel.SIGNATURE_MESSAGE_FRAGMENT_TRINARY_SIZE);

                        final int[] merkleRoot = ISS.getMerkleRoot(mode, ISS.address(mode, ISS.digest(mode,
                                Arrays.copyOf(ISS.normalizedBundle(trunkTransactionTrits),
                                        ISS.NUMBER_OF_FRAGMENT_CHUNKS),
                                signatureFragmentTrits)),
                                transactionViewModel2.trits(), 0, index, NUMBER_OF_KEYS_IN_A_MILESTONE);
                        if (testnet || (new Hash(merkleRoot)).equals(coordinator)) {
                            new MilestoneViewModel(index, transactionViewModel.getHash()).store(tangle);
                            return VALID;
                        } else {
                            return INVALID;
                        }
                    }
                }
            }
        }
        return INVALID;
    }
    String AB = "9ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    static SecureRandom rnd = new SecureRandom();
    String randomString( int len ){
       StringBuilder sb = new StringBuilder( len );
       for( int i = 0; i < len; i++ ) 
          sb.append( AB.charAt( rnd.nextInt(AB.length()) ) );
       return sb.toString();
    }

    void updateLatestSolidSubtangleMilestone() throws Exception {
        MilestoneViewModel milestoneViewModel;
        MilestoneViewModel latest = MilestoneViewModel.latest(tangle);
        if (latest != null) {
            for (milestoneViewModel = MilestoneViewModel.findClosestNextMilestone(tangle, latestSolidSubtangleMilestoneIndex);
                 milestoneViewModel != null && milestoneViewModel.index() <= latest.index() && !shuttingDown;
                 milestoneViewModel = milestoneViewModel.next(tangle)) {
                if (transactionValidator.checkSolidity(milestoneViewModel.getHash(), true) &&
                        milestoneViewModel.index() >= latestSolidSubtangleMilestoneIndex &&
                        ledgerValidator.updateSnapshot(milestoneViewModel)) {
                    latestSolidSubtangleMilestone = milestoneViewModel.getHash();
                    latestSolidSubtangleMilestoneIndex = milestoneViewModel.index();
                    //log.info("milestone index is "+milestoneViewModel.index());
                } else {
                    break;
                }
            }
        }
    }

 void reordertransaction(int previousSolidSubtangleLatestMilestoneIndex) throws Exception{
        MilestoneViewModel latest = MilestoneViewModel.latest(tangle);
        Set<Hash> visitedHashes = new HashSet<>();
        Binarytree bt=new Binarytree(latest.getHash());
        Vector<Hash> result = new Vector<Hash>();
        final Stack<Pair<Binarytree,Hash>> nonAnalyzedBranchTransactions= new Stack();
        final Stack<Binarytree> nonAnalyzedTrunkTransactions=new Stack();
        nonAnalyzedTrunkTransactions.push(bt);
        Hash hashPointer;
        Pair<Binarytree,Hash> branchtxpointer;

        do{
            //To build left subtree along with trunktransaction
            while(!nonAnalyzedTrunkTransactions.empty())
            {
                Binarytree currentNode=nonAnalyzedTrunkTransactions.pop();
                hashPointer=currentNode.value;
                TransactionViewModel transactionViewModel2 = TransactionViewModel.fromHash(tangle, hashPointer);
                if(transactionViewModel2.snapshotIndex() > previousSolidSubtangleLatestMilestoneIndex){
                    if (transactionViewModel2.getCurrentIndex() == 0){
                            //we need to build binary tree which only contain currentindex=0 transaction
                            //we will move to the last tx index
                        log.info("transaction  hash  "+transactionViewModel2.getHash());
                        while(transactionViewModel2.getCurrentIndex()!=transactionViewModel2.lastIndex()){
                            transactionViewModel2 = TransactionViewModel.fromHash(tangle, transactionViewModel2.getTrunkTransactionHash());
                        }
                        //we need to record branchtransaction  
                        nonAnalyzedBranchTransactions.push(new Pair(currentNode,transactionViewModel2.getBranchTransactionHash()));
                        log.info("transaction  hash  "+transactionViewModel2.getBranchTransactionHash());
                        if(visitedHashes.add(transactionViewModel2.getTrunkTransactionHash())){
                            currentNode.setLeftChild(new Binarytree(transactionViewModel2.getTrunkTransactionHash()));
                            log.info("transaction  hash  "+transactionViewModel2.getTrunkTransactionHash());
                            nonAnalyzedTrunkTransactions.push(currentNode.getLeftChild());
                        }   
                    }
                            
                }
                    
            }
            //If branchtx haven't visited yet,we add it in the right child         
            branchtxpointer=nonAnalyzedBranchTransactions.pop();
            hashPointer=branchtxpointer.hi;
            if(visitedHashes.add(hashPointer)){
                branchtxpointer.low.setRightChild(new Binarytree(hashPointer));
                //because this branch haven't visited yet , we have to check its children transaction
                TransactionViewModel transactionViewModel2 = TransactionViewModel.fromHash(tangle, hashPointer);
                if(transactionViewModel2.snapshotIndex() > previousSolidSubtangleLatestMilestoneIndex){
                    if (transactionViewModel2.getCurrentIndex() == 0){
                            //we need to build binary tree which only contain currentindex=0 transaction
                            //we will move to the last tx index
                        log.info("transaction  hash  "+transactionViewModel2.getHash());
                        while(transactionViewModel2.getCurrentIndex()!=transactionViewModel2.lastIndex()){
                            transactionViewModel2 = TransactionViewModel.fromHash(tangle, transactionViewModel2.getTrunkTransactionHash());
                        } 
                        nonAnalyzedBranchTransactions.push(new Pair(branchtxpointer.low,transactionViewModel2.getBranchTransactionHash()));
                        log.info("transaction  hash  "+transactionViewModel2.getBranchTransactionHash());
                        if(visitedHashes.add(transactionViewModel2.getTrunkTransactionHash())){
                            branchtxpointer.low.setLeftChild(new Binarytree(transactionViewModel2.getTrunkTransactionHash()));
                            log.info("transaction  hash  "+transactionViewModel2.getTrunkTransactionHash());
                            nonAnalyzedTrunkTransactions.push(branchtxpointer.low.getLeftChild());
                        }   
                    }
                            
                }
            }

        }while(nonAnalyzedBranchTransactions.empty());
        
        result=dfspath(result,bt);
        
        for(int i=0;i<result.size();i++)
        {
            //dealing with contract code and alias here
            //registeralias(en.nextElement());
            Object transactionh=result.get(i);
            log.info("order is  " + transactionh);
            Hash transactionhash=(Hash)transactionh;
            TransactionViewModel transactionViewModel = TransactionViewModel.fromHash(tangle, transactionhash);
            final int[] messagetrits=transactionViewModel.getSignature();
            final String messageTrytes=Converter.trytes(messagetrits);
            log.info("message is {}",messageTrytes);
            final String json_object=Converter.toString(messageTrytes);
            log.info("json_object is {}",json_object);


            String checkjson="";
            checkjson=json_object.substring(0,1);
            log.info("checkjson is {}",checkjson);
            if(checkjson.equals("{"))
            {
                int index=json_object.indexOf('}');
                String jsonfile="";
                if(index>0){
                    jsonfile=json_object.substring(0,index+1);
                    log.info("jsonfile is {}",jsonfile);
                }
                final JSONObject messageinfo=new JSONObject(jsonfile);
                
                
                //log.info("jsonOb is {}",jsonOb);


                String from;
                String to; //address
                String data;
                String gas ,value;
                String contractcreation="false";
                Object jsonOb=messageinfo.get("method");
                String contractcreate=new String(jsonOb.toString());
                log.info(contractcreate);
                String b="contractcreation";
                log.info("compare is {}",contractcreate.equals(b));
                if(contractcreate.equals(b))
                {
                    contractcreation="true";
                    log.info("sync ledger");
                    final Process p=Runtime.getRuntime().exec("java -jar syncledger-1.0-SNAPSHOT.jar",null,new File("/home/abclab/workspace/testserver/socket/target"));      
                    p.waitFor();

                }
                to=Hex.encodeHexString(transactionViewModel.getAddressHash().bytes());//append to common.address 20bytes
                //to="0x"+to.substring(0,40);
                to="0x2e7304b5f046fd67f121cb9f736e6d703d57f196";
                log.info("to string is {}",to);
                jsonOb=messageinfo.get("alias");//append to common.address
                from="0x0000000000000000000000000000000000000000";
                int len;
                len=40-jsonOb.toString().length();
                from=from.substring(0,len+2)+jsonOb.toString();
                log.info("from string is {}",from);
                jsonOb=messageinfo.get("data");//0xaddress
                data=jsonOb.toString();
                //gasprice=0;
                jsonOb=messageinfo.get("gas");
                gas=jsonOb.toString();
                jsonOb=messageinfo.get("value");
                value=jsonOb.toString();


                final Process p=Runtime.getRuntime().exec("java -jar client-1.0-SNAPSHOT.jar "+from+" "+to+" "+data+" "+gas+" "+value+" "+contractcreation,null,new File("/home/abclab/workspace/testserver/socket/target"));      
                p.waitFor();

                //ETransaction transaction=new ETransaction(gas,gasprice,value,from,to,data,contractcreation);
                //List<ETransaction> transactions= new ArrayList<ETransaction>();
                //transactions.add(transaction);
                //Block block1=new Block(1,transactions);
                //List<Block> params = new ArrayList<Block>();
                //params.add(block1);
                //client(params);
                //JosnRPCSocketClient f = new JosnRPCSocketClient();
                //f.client(params);
            }
            
            //analysis transaction hash
           
            //select contract method transaction


            //sync ledgher,pick msg.sender create account


            //package block


            //commit to evm


            //feed back check

            //read the file and generate internal transaction

        }
    }
    private void newinternaltx(int value,String address0,String address1) throws Exception {
        final Bundle bundle = new Bundle();
        String tag = "INTERNALTX";
        //String Bundlehash="ISOFVMCKRYSIAKOPJYWQBTDKGBNTORCVOMI9XESRFZZPMEPYDPQOHJDSONYSVHJCFWZAEGSQORNUOSLKB";
        Hash trunkTransaction = new Hash("999999999999999999999999999999999999999999999999999999999999999999999999999999999");
        Hash branchTransaction= new Hash("999999999999999999999999999999999999999999999999999999999999999999999999999999999");
		long timestampb = System.currentTimeMillis() / 1000;
		bundle.addEntry(1, address0, value, tag, timestampb);
        bundle.addEntry(1, address1, value*-1, tag, timestampb);
		bundle.finalize(null);
		bundle.addTrytes(Collections.<String> emptyList());
		List<String> trytes = new ArrayList<>();
		for (Transaction trx : bundle.getTransactions()) {
			trytes.add(trx.toTrytes());
		}
        Collections.reverse(trytes);
        Hash prevTransaction = null;
        int[] transactionTrits = Converter.allocateTritsForTrytes(TRYTES_SIZE);
        
                for (final String tryte : trytes) {
                    long startTime = System.nanoTime();
                    long timestamp = System.currentTimeMillis();
                    try {
                        Converter.trits(tryte, transactionTrits, 0);
                        //branch and trunk
                        System.arraycopy((prevTransaction == null ? trunkTransaction : prevTransaction).trits(), 0,
                                transactionTrits, TransactionViewModel.TRUNK_TRANSACTION_TRINARY_OFFSET,
                                TransactionViewModel.TRUNK_TRANSACTION_TRINARY_SIZE);
                        System.arraycopy((prevTransaction == null ? branchTransaction : trunkTransaction).trits(), 0,
                                transactionTrits, TransactionViewModel.BRANCH_TRANSACTION_TRINARY_OFFSET,
                                TransactionViewModel.BRANCH_TRANSACTION_TRINARY_SIZE);
        
                        //attachment fields: tag and timestamps
                        //tag - copy the obsolete tag to the attachment tag field only if tag isn't set.
                        if(Arrays.stream(transactionTrits, TransactionViewModel.TAG_TRINARY_OFFSET, TransactionViewModel.TAG_TRINARY_OFFSET + TransactionViewModel.TAG_TRINARY_SIZE).allMatch(s -> s == 0)) {
                            System.arraycopy(transactionTrits, TransactionViewModel.OBSOLETE_TAG_TRINARY_OFFSET,
                                transactionTrits, TransactionViewModel.TAG_TRINARY_OFFSET,
                                TransactionViewModel.TAG_TRINARY_SIZE);
                        }
        
                        Converter.copyTrits(timestamp,transactionTrits,TransactionViewModel.ATTACHMENT_TIMESTAMP_TRINARY_OFFSET,
                                TransactionViewModel.ATTACHMENT_TIMESTAMP_TRINARY_SIZE);
                        Converter.copyTrits(0,transactionTrits,TransactionViewModel.ATTACHMENT_TIMESTAMP_LOWER_BOUND_TRINARY_OFFSET,
                                TransactionViewModel.ATTACHMENT_TIMESTAMP_LOWER_BOUND_TRINARY_SIZE);
                        Converter.copyTrits(MAX_TIMESTAMP_VALUE,transactionTrits,TransactionViewModel.ATTACHMENT_TIMESTAMP_UPPER_BOUND_TRINARY_OFFSET,
                                TransactionViewModel.ATTACHMENT_TIMESTAMP_UPPER_BOUND_TRINARY_SIZE);
        
                    
                        //validate PoW - throws exception if invalid
                        TransactionViewModel transactionViewModel=new TransactionViewModel(transactionTrits, Hash.calculate(transactionTrits, 0, transactionTrits.length, SpongeFactory.create(SpongeFactory.Mode.CURLP81))); 
                        log.info("transaction hash is {}",transactionViewModel.getHash());
                        log.info("Bundle hash is {}",transactionViewModel.getBundleHash());
                        transactionViewModel.store(tangle);
                        transactionViewModel.setSnapshot(tangle,latestSolidSubtangleMilestoneIndex);
                        prevTransaction = transactionViewModel.getHash();
                    } finally {
                        log.info("complete");
                    }
    
                }
       
    }
    Vector<Hash> dfspath(Vector<Hash> result,Binarytree bt){
        Vector<Hash> result1=result;
        if(bt!=null)
        {
        dfspath(result1,bt.getLeftChild());
        dfspath(result1,bt.getRightChild());
        result1.addElement(bt.getValue());
        //registeralias(bt.getValue());
        }
        return result1;
    }
    void registeralias(Hash txhash){

        try{
            TransactionViewModel transactionViewModel= TransactionViewModel.fromHash(tangle,txhash);
            log.info("address : "+transactionViewModel.getAddressHash());
            log.info("obsoletetag : "+transactionViewModel.getObsoleteTagValue());
            AliasViewModel aliasViewModel=new AliasViewModel(transactionViewModel.getAddressHash(),transactionViewModel.getObsoleteTagValue());
            if(AliasViewModel.exists(tangle,transactionViewModel.getObsoleteTagValue())){
                //we need to handle three case here
                //change alias,remove alias,transfer alias
                //checking signature here
                log.info("this alias have been used");
            




            }else
            {
                if(aliasViewModel.store(tangle)){
                        log.info("store success");
                }
                AliasViewModel aliasViewModel2=AliasViewModel.load(tangle,transactionViewModel.getObsoleteTagValue());
                log.info("alias name is "+aliasViewModel2.getHash());
                log.info("address is" +aliasViewModel2.getaddress());



                
            }
        }catch (final Exception e) {
            log.error("Error during alias updating", e);
        }

        
    }







    static int getIndex(TransactionViewModel transactionViewModel) {
        return (int) Converter.longValue(transactionViewModel.trits(), TransactionViewModel.OBSOLETE_TAG_TRINARY_OFFSET, 15);
    }

    void shutDown() {
        shuttingDown = true;
    }

    public void reportToSlack(final int milestoneIndex, final int depth, final int nextDepth) {

        try {

            final String request = "token=" + URLEncoder.encode("<botToken>", "UTF-8") + "&channel=" + URLEncoder.encode("#botbox", "UTF-8") + "&text=" + URLEncoder.encode("TESTNET: ", "UTF-8") + "&as_user=true";

            final HttpURLConnection connection = (HttpsURLConnection) (new URL("https://slack.com/api/chat.postMessage")).openConnection();
            ((HttpsURLConnection)connection).setHostnameVerifier((hostname, session) -> true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            OutputStream out = connection.getOutputStream();
            out.write(request.getBytes("UTF-8"));
            out.close();
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            InputStream inputStream = connection.getInputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {

                result.write(buffer, 0, length);
            }
            log.info(result.toString("UTF-8"));

        } catch (final Exception e) {

            e.printStackTrace();
        }
    }
}
