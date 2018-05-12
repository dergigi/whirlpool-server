package com.samourai.whirlpool.server.services;

import com.samourai.wallet.bip69.BIP69InputComparator;
import com.samourai.wallet.bip69.BIP69OutputComparator;
import com.samourai.wallet.segwit.bech32.Bech32Util;
import com.samourai.whirlpool.protocol.v1.messages.PeersPaymentCodesResponse;
import com.samourai.whirlpool.protocol.v1.messages.RegisterInputResponse;
import com.samourai.whirlpool.protocol.v1.notifications.*;
import com.samourai.whirlpool.server.beans.*;
import com.samourai.whirlpool.server.config.WhirlpoolServerConfig;
import com.samourai.whirlpool.server.controllers.v1.RegisterOutputController;
import com.samourai.whirlpool.server.exceptions.IllegalInputException;
import com.samourai.whirlpool.server.exceptions.RoundException;
import com.samourai.whirlpool.server.utils.Utils;
import org.bitcoinj.core.*;
import org.bitcoinj.script.ScriptException;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.lang.invoke.MethodHandles;
import java.util.*;

@Service
public class RoundService {
    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    //private Map<String,Round> rounds;
    private WebSocketService webSocketService;
    private CryptoService cryptoService;
    private BlameService blameService;
    private DbService dbService;
    private BlockchainDataService blockchainDataService;
    private RoundLimitsManager roundLimitsManager;
    private Bech32Util bech32Util;
    private WhirlpoolServerConfig whirlpoolServerConfig;

    private Round currentRound;

    private boolean deterministPaymentCodeMatching; // for testing purpose only

    @Autowired
    public RoundService(CryptoService cryptoService, BlameService blameService, DbService dbService, BlockchainDataService blockchainDataService, WebSocketService webSocketService, Bech32Util bech32Util, WhirlpoolServerConfig whirlpoolServerConfig) {
        this.cryptoService = cryptoService;
        this.blameService = blameService;
        this.dbService = dbService;
        this.blockchainDataService = blockchainDataService;
        this.webSocketService = webSocketService;
        this.bech32Util = bech32Util;
        this.whirlpoolServerConfig = whirlpoolServerConfig;

        this.roundLimitsManager = new RoundLimitsManager(this, blameService, whirlpoolServerConfig);
        this.deterministPaymentCodeMatching = false;

        WhirlpoolServerConfig.RoundConfig roundConfig = whirlpoolServerConfig.getRound();
        String roundId = generateRoundId();
        long denomination = whirlpoolServerConfig.getRound().getDenomination();
        long fees = roundConfig.getMinerFee();
        int targetMustMix = roundConfig.getTargetMustMix();
        int minMustMix = roundConfig.getMinMustMix();
        long mustMixAdjustTimeout = roundConfig.getMustMixAdjustTimeout();
        float liquidityRatio = roundConfig.getLiquidityRatio();
        Round round = new Round(roundId, denomination, fees, targetMustMix, minMustMix, mustMixAdjustTimeout, liquidityRatio);
        this.__reset(round);
    }

    private String generateRoundId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public synchronized void registerInput(String roundId, String username, TxOutPoint input, byte[] pubkey, String paymentCode, byte[] signedBordereauToReply, boolean liquidity) throws IllegalInputException, RoundException {
        log.info("registerInput "+roundId+" : "+username+" : "+input);
        Round round = getRound(roundId, RoundStatus.REGISTER_INPUT);
        if (!checkInputBalance(input, round, liquidity)) {
            throw new IllegalInputException("Input balance should match denomination+fees");
        }

        RegisteredInput registeredInput = new RegisteredInput(username, input, pubkey, paymentCode, liquidity);
        if (!liquidity) {
            /*
             * user wants to mix
             */
            registerInput(round, registeredInput, signedBordereauToReply, false);
        }
        else {
            /*
             * user is providing liquidity
             */
            LiquidityPool liquidityPool = roundLimitsManager.getLiquidityPool(round);
            if (liquidityPool.hasLiquidity(input)) {
                throw new IllegalInputException("Liquidity already registered for this round");
            }

            // queue liquidity for later
            RegisteredLiquidity registeredInputQueued = new RegisteredLiquidity(registeredInput, signedBordereauToReply);
            liquidityPool.registerLiquidity(registeredInputQueued);
        }

    }

    private synchronized void registerInput(Round round, RegisteredInput registeredInput, byte[] signedBordereauToReply, boolean isLiquidity) throws IllegalInputException, RoundException {
        TxOutPoint input = registeredInput.getInput();
        String username = registeredInput.getUsername();

        int inputsLimit = round.getTargetMustMix();
        if (isLiquidity) {
            inputsLimit += round.computeLiquiditiesExpected();
        }
        if (round.getNbInputs() >= inputsLimit) {
            throw new RoundException("Round inputs are full, please wait for next round");
        }
        if (round.hasInput(input)) {
            throw new IllegalInputException("Input already registered for this round");
        }

        // add immediately to round inputs
        round.registerInput(registeredInput);
        roundLimitsManager.onInputRegistered(round);

        // response
        RegisterInputResponse registerInputResponse = new RegisterInputResponse();
        registerInputResponse.signedBordereau = signedBordereauToReply;
        webSocketService.sendPrivate(username, registerInputResponse);


        if (isRegisterInputReady(round)) {
            changeRoundStatus(round.getRoundId(), RoundStatus.REGISTER_OUTPUT);
        }
    }

    public void addLiquidity(Round round, RegisteredLiquidity randomLiquidity) throws Exception {
        registerInput(round, randomLiquidity.getRegisteredInput(), randomLiquidity.getSignedBordereau(), true);
    }

    private long computeSpendAmount(Round round, boolean liquidity) {
        if (liquidity) {
            // no minersFees for liquidities
            return round.getDenomination();
        }
        return round.getDenomination() + round.getFees();
    }

    private boolean checkInputBalance(TxOutPoint input, Round round, boolean liquidity) {
        // input balance should match exactly this amount, because we don't generate change
        long spendAmount = computeSpendAmount(round, liquidity);
        return (input.getValue() == spendAmount);
    }

    protected boolean isRegisterInputReady(Round round) {
        if (round.getNbInputs() == 0) {
            return false;
        }
        if (!isRegisterInputReadyNbInputs(round.getNbInputs(), round.getTargetMustMix(), round.computeLiquiditiesExpected())) {
            return false;
        }
        return true;
    }

    protected synchronized boolean isRegisterInputReadyNbInputs(int nbInputs, int targetMustMix, int nbLiquiditiesExpected) {
        int nbInputsExpected = targetMustMix; // mustMix
        nbInputsExpected += nbLiquiditiesExpected;
        return nbInputs == nbInputsExpected;
    }

    public synchronized void registerOutput(String roundId, String sendAddress, String receiveAddress, String bordereau) throws Exception {
        log.info("addOutput "+roundId+" : "+sendAddress+" "+receiveAddress);
        Round round = getRound(roundId, RoundStatus.REGISTER_OUTPUT);
        round.registerOutput(sendAddress, receiveAddress, bordereau);

        if (isRegisterOutputReady(round)) {
            validateOutputs(round);
            changeRoundStatus(roundId, RoundStatus.SIGNING);
        }
    }

    protected void validateOutputs(Round round) throws Exception {
        // sendAddresses and receiveAddresses should match (this verifies no user is cheating another one)
        if (!Utils.listEqualsIgnoreOrder(round.getSendAddresses(), round.getReceiveAddresses())) {
            log.error("sendAddresses doesn't match receiveAddresses. sendAddresses="+round.getSendAddresses()+"receiveAddresses="+round.getReceiveAddresses());
            throw new Exception("REGISTER_OUTPUT failed"); // TODO find and ban malicious users
        }
    }

    protected synchronized boolean isRegisterOutputReady(Round round) {
        if (!isRegisterInputReady(round)) {
            // TODO recheck inputs balances and update/ban/reopen REGISTER_INPUT or fail if input spent in the meantime
            return false;
        }
        return (round.getSendAddresses().size() == round.getNbInputs());
    }

    public synchronized void revealOutput(String roundId, String username, String bordereau) throws RoundException, IllegalInputException {
        log.info("revealOutput "+roundId+" : "+bordereau);
        Round round = getRound(roundId, RoundStatus.REVEAL_OUTPUT_OR_BLAME);

        // verify an output was registered with this bordereau
        if (!round.getRegisteredBordereaux().contains(bordereau)) {
            throw new IllegalInputException("Invalid bordereau");
        }
        // verify this bordereau was not already revealed (someone could try to register 2 inputs and reveal same bordereau to block round)
        if (round.getRevealedOutputUsers().contains(bordereau)) {
            log.warn("Rejecting already revealed bordereau: "+bordereau);
            throw new IllegalInputException("Bordereau already revealed");
        }
        round.addRevealedOutputUser(username);

        if (isRevealOutputOrBlameReady(round)) {
            roundLimitsManager.blameForRevealOutputAndResetRound(round);
        }
    }

    protected synchronized boolean isRevealOutputOrBlameReady(Round round) {
        return (round.getRevealedOutputUsers().size() == round.getNbInputs());
    }

    public synchronized void registerSignature(String roundId, String username, byte[][] witness) throws Exception {
        log.info("setSignaturesByUsername "+roundId+" : "+username);
        Round round = getRound(roundId, RoundStatus.SIGNING);
        Signature signature = new Signature(witness);
        round.setSignatureByUsername(username, signature);

        if (isRegisterSignaturesReady(round)) {
            Transaction tx = round.getTx();
            signTransaction(tx, round); // updates Transaction object itself

            log.info("Signed tx: "+tx+"\nraw: " + org.bitcoinj.core.Utils.HEX.encode(tx.bitcoinSerialize()));
            try {
                blockchainDataService.broadcastTransaction(tx);
            }
            catch(Exception e) {
                dbService.saveRound(round, RoundResult.FAIL_BROADCAST);
            }

            changeRoundStatus(roundId, RoundStatus.SUCCESS);
        }
    }

    protected synchronized boolean isRegisterSignaturesReady(Round round) {
        if (!isRegisterOutputReady(round)) {
            return false;
        }
        return (round.getNbSignatures() == round.getNbInputs());
    }

    public String getCurrentRoundId() {
        return currentRound.getRoundId();
    }

    public Round __getCurrentRound() {
        return currentRound;
    }

    public void changeRoundStatus(String roundId, RoundStatus roundStatus) {
        log.info("### changeRoundStatus "+roundId+" ==> "+roundStatus);
        try {
            Round round = getRound(roundId);

            if (roundStatus == RoundStatus.REGISTER_OUTPUT) {
                transmitPaymentCodes(round);
            } else if (roundStatus == RoundStatus.SIGNING) {
                try {
                    Transaction tx = computeTransaction(round);
                    round.setTx(tx);

                    log.info("Tx to sign: "+tx+"\nraw: " + org.bitcoinj.core.Utils.HEX.encode(tx.bitcoinSerialize()));
                } catch (Exception e) {
                    log.error("Unexpected exception on buildTransaction() for signing", e);
                    throw new RoundException("System error");
                }
            }

            // update round status
            round.setRoundStatusAndTime(roundStatus);
            roundLimitsManager.onRoundStatusChange(round);

            RoundStatusNotification roundStatusNotification = computeRoundStatusNotification();
            webSocketService.broadcast(roundStatusNotification);

            // start next round (after notifying clients for success)
            if (roundStatus == RoundStatus.SUCCESS) {
                dbService.saveRound(round, RoundResult.SUCCESS);
                __nextRound();
            } else if (roundStatus == RoundStatus.FAIL) {
                dbService.saveRound(round, round.getFailReason());
                __nextRound();
            }
        }
        catch(RoundException e) {
            log.error("Unexpected round error", e);
            __nextRound();
        }
    }

    private void transmitPaymentCodes(Round round) throws RoundException {
        // get all paymentCodes associated to users
        Map<String,String> paymentCodesByUser = new HashMap<>();
        for (RegisteredInput registeredInput : round.getInputs()) {
            String paymentCode = registeredInput.getPaymentCode();
            String username = registeredInput.getUsername();
            paymentCodesByUser.put(username, paymentCode);
        }

        // determinist paymentCodes matching for tests reproductibility
        if (deterministPaymentCodeMatching) {
            log.warn("deterministPaymentCodeMatching is enabled (use it for tests only!)");
            // sort by paymentCode
            paymentCodesByUser = Utils.sortMapByValue(paymentCodesByUser);
        }

        // confrontate paymentCodes
        Map<String,PeersPaymentCodesResponse> peersPaymentCodesResponsesByUser = computePaymentCodesConfrontations(paymentCodesByUser);

        // transmit to users
        for (Map.Entry<String,PeersPaymentCodesResponse> peersPaymentCodesResponseEntry : peersPaymentCodesResponsesByUser.entrySet()) {
            webSocketService.sendPrivate(peersPaymentCodesResponseEntry.getKey(), peersPaymentCodesResponseEntry.getValue());
        }
    }

    protected Map<String,PeersPaymentCodesResponse> computePaymentCodesConfrontations(Map<String, String> paymentCodesByUser) {
        List<String> usernames = new ArrayList(paymentCodesByUser.keySet());
        List<String> paymentCodes = new ArrayList(paymentCodesByUser.values());

        Map<String,PeersPaymentCodesResponse> peersPaymentCodesResponsesByUser = new HashMap<>();
        for (int i=0; i<usernames.size(); i++) {
            // for each registered user...
             String username = usernames.get(i);

            // pick a paymentcode to confrontate with to compute receiveAddress
            int iFromPaymentCode = (i==paymentCodes.size()-1 ? 0 : i+1);
            String fromPaymentCode = paymentCodes.get(iFromPaymentCode);

            // pick reverse paymentcode to confrontate with to compute sendAddress (for mutual validation)
            int iToPaymentCode = (i==0 ? paymentCodes.size()-1 : i-1);
            String toPaymentCode = paymentCodes.get(iToPaymentCode);

            // send
            PeersPaymentCodesResponse confrontatePaymentCodeResponse = new PeersPaymentCodesResponse();
            confrontatePaymentCodeResponse.fromPaymentCode = fromPaymentCode;
            confrontatePaymentCodeResponse.toPaymentCode = toPaymentCode;
            peersPaymentCodesResponsesByUser.put(username, confrontatePaymentCodeResponse);
        }
        return peersPaymentCodesResponsesByUser;
    }

    public RoundStatusNotification computeRoundStatusNotification() throws RoundException {
        String roundId = getCurrentRoundId();
        Round round = getRound(roundId);
        RoundStatusNotification roundStatusNotification = null;
        switch(round.getRoundStatus()) {
            case REGISTER_INPUT:
                try {
                    byte[] publicKey = cryptoService.getPublicKey().getEncoded();
                    roundStatusNotification = new RegisterInputRoundStatusNotification(roundId, publicKey, cryptoService.getNetworkParameters().getPaymentProtocolId(), round.getDenomination(), round.getFees());
                }
                catch(Exception e) {
                    throw new RoundException("unexpected error"); // TODO
                }
                break;
            case REGISTER_OUTPUT:
                String registerOutputUrl = computeRegisterOutputUrl();
                roundStatusNotification = new RegisterOutputRoundStatusNotification(roundId, registerOutputUrl);
                break;
            case REVEAL_OUTPUT_OR_BLAME:
                roundStatusNotification = new RevealOutputOrBlameRoundStatusNotification(roundId);
                break;
            case SIGNING:
                roundStatusNotification = new SigningRoundStatusNotification(roundId, round.getTx().bitcoinSerialize());
                break;
            case SUCCESS:
                roundStatusNotification = new SuccessRoundStatusNotification(roundId);
                break;
            case FAIL:
                roundStatusNotification = new FailRoundStatusNotification(roundId);
                break;
        }
        return roundStatusNotification;
    }

    private String computeRegisterOutputUrl() {
        String registerOutputUrl = whirlpoolServerConfig.getRegisterOutput().getUrl() + RegisterOutputController.ENDPOINT;
        return registerOutputUrl;
    }

    private Round getRound(String roundId) throws RoundException {
        //Round round = rounds.get(roundId);
        //if (round == null) {
        if (!currentRound.getRoundId().equals(roundId)) {
            throw new RoundException("Invalid roundId");
        }
        return currentRound;
    }

    private Round getRound(String roundId, RoundStatus roundStatus) throws RoundException {
        Round round = getRound(roundId);
        if (!roundStatus.equals(round.getRoundStatus())) {
            throw new RoundException("Operation not permitted for current round status");
        }
        return round;
    }

    private Transaction computeTransaction(Round round) throws Exception {
        NetworkParameters params = cryptoService.getNetworkParameters();
        Transaction tx = new Transaction(params);
        List<TransactionInput> inputs = new ArrayList<>();
        List<TransactionOutput> outputs = new ArrayList<>();

        tx.clearOutputs();
        for (String receiveAddress : round.getReceiveAddresses()) {
            TransactionOutput txOutSpend = bech32Util.getTransactionOutput(receiveAddress, round.getDenomination(), params);
            if (txOutSpend == null) {
                throw new Exception("unable to create output for "+receiveAddress);
            }
            outputs.add(txOutSpend);
        }

        //
        // BIP69 sort outputs
        //
        Collections.sort(outputs, new BIP69OutputComparator());
        for(TransactionOutput to : outputs) {
            tx.addOutput(to);
        }

        //
        // create 1 mix tx
        //
        for (RegisteredInput registeredInput : round.getInputs()) {
            // send from bech32 input
            long spendAmount = computeSpendAmount(round, registeredInput.isLiquidity());
            TxOutPoint registeredOutPoint = registeredInput.getInput();
            TransactionOutPoint outPoint = new TransactionOutPoint(params, registeredOutPoint.getIndex(), Sha256Hash.wrap(registeredOutPoint.getHash()), Coin.valueOf(spendAmount));
            TransactionInput txInput = new TransactionInput(params, null, new byte[]{}, outPoint, Coin.valueOf(spendAmount));
            inputs.add(txInput);
        }

        //
        // BIP69 sort inputs
        //
        Collections.sort(inputs, new BIP69InputComparator());
        for(TransactionInput ti : inputs) {
            tx.addInput(ti);
        }
        return tx;
    }

    private void signTransaction(Transaction tx, Round round) {
        for (RegisteredInput registeredInput : round.getInputs()) {
            Signature signature = round.getSignatureByUsername(registeredInput.getUsername());

            TxOutPoint registeredOutPoint = registeredInput.getInput();
            Integer inputIndex = Utils.findTxInput(tx, registeredOutPoint.getHash(), registeredOutPoint.getIndex());
            if (inputIndex == null) {
                throw new ScriptException("Transaction input not found");
            }

            TransactionWitness witness = Utils.witnessUnserialize(signature.witness);
            tx.setWitness(inputIndex, witness);
        }

        // check final transaction
        tx.verify();
    }

    public void goRevealOutputOrBlame(String roundId) {
        changeRoundStatus(roundId, RoundStatus.REVEAL_OUTPUT_OR_BLAME);
    }

    public void goFail(Round round, RoundResult roundResult) {
        round.setFailReason(roundResult);
        changeRoundStatus(round.getRoundId(), RoundStatus.FAIL);
    }

    public void __reset(String roundId) {
        Round copyRound = new Round(roundId, this.currentRound);
        __reset(copyRound);
    }

    public void __nextRound() {
        String roundId = generateRoundId();
        __reset(roundId);
    }

    public void __reset(Round round) {
        log.info("New round ==> "+round.getRoundId());
        if (this.currentRound != null) {
            roundLimitsManager.unmanage(round);
        }
        this.currentRound = round;
        // TODO disconnect all clients (except liquidities?)
        roundLimitsManager.manage(round);
    }

    public RoundLimitsManager __getRoundLimitsManager() {
        return roundLimitsManager;
    }

    public void __setUseDeterministPaymentCodeMatching(boolean useDeterministPaymentCodeMatching) {
        this.deterministPaymentCodeMatching = useDeterministPaymentCodeMatching;
    }
}