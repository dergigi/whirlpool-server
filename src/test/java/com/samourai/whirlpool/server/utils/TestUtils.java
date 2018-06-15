package com.samourai.whirlpool.server.utils;

import com.samourai.wallet.bip47.rpc.BIP47Wallet;
import com.samourai.wallet.hd.HD_Wallet;
import com.samourai.wallet.segwit.SegwitAddress;
import com.samourai.wallet.segwit.bech32.Bech32Util;
import com.samourai.whirlpool.server.beans.*;
import com.samourai.whirlpool.server.services.BlockchainDataService;
import com.samourai.whirlpool.server.services.CryptoService;
import com.samourai.whirlpool.server.services.TestBlockchainDataService;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.wallet.KeyChain;
import org.bitcoinj.wallet.KeyChainGroup;
import org.bouncycastle.util.encoders.Hex;
import org.mockito.Mockito;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Arrays;

@Service
public class TestUtils {
    private CryptoService cryptoService;
    protected Bech32Util bech32Util;
    protected BlockchainDataService blockchainDataService;

    public TestUtils(CryptoService cryptoService, Bech32Util bech32Util, BlockchainDataService blockchainDataService) {
        this.cryptoService = cryptoService;
        this.bech32Util = bech32Util;
        this.blockchainDataService = blockchainDataService;
    }

    public SegwitAddress createSegwitAddress() throws Exception {
        //BIP47WalletAndHDWallet inputWallets = generateWallet(44);
        //HD_Wallet inputWallet = inputWallets.getHdWallet();
        //ECKey utxoKey = inputWallet.getAccount(0).getReceive().getAddressAt(0).getECKey();

        KeyChainGroup kcg = new KeyChainGroup(cryptoService.getNetworkParameters());
        DeterministicKey utxoKey = kcg.freshKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        SegwitAddress p2shp2wpkh = new SegwitAddress(utxoKey, cryptoService.getNetworkParameters());
        return p2shp2wpkh;
    }

    public BIP47WalletAndHDWallet generateWallet(int purpose, byte[] seed, String passphrase) throws Exception {
        final String BIP39_ENGLISH_SHA256 = "ad90bf3beb7b0eb7e5acd74727dc0da96e0a280a258354e7293fb7e211ac03db";
        InputStream wis = HD_Wallet.class.getResourceAsStream("/en_US.txt");
        if (wis != null) {
            MnemonicCode mc = new MnemonicCode(wis, BIP39_ENGLISH_SHA256);

            // init BIP44 wallet for input
            HD_Wallet inputWallet = new HD_Wallet(purpose, mc, cryptoService.getNetworkParameters(), seed, passphrase, 1);
            // init BIP47 wallet for input
            BIP47Wallet bip47InputWallet = new BIP47Wallet(47, inputWallet, 1);

            wis.close();
            return new BIP47WalletAndHDWallet(bip47InputWallet, inputWallet);
        }
        throw new Exception("wis is null");
    }

    public BIP47WalletAndHDWallet generateWallet(int purpose) throws Exception {
        int nbWords = 12;
        // len == 16 (12 words), len == 24 (18 words), len == 32 (24 words)
        int len = (nbWords / 3) * 4;

        SecureRandom random = new SecureRandom();
        byte seed[] = new byte[len];
        random.nextBytes(seed);

        return generateWallet(purpose, seed, "test");
    }

    public long computeSpendAmount(Round round, boolean liquidity) {
        if (liquidity) {
            // no minerFees for liquidities
            return round.getDenomination();
        }
        return round.getDenomination() + round.getFees();
    }

    public TxOutPoint createAndMockTxOutPoint(SegwitAddress address, long amount) throws Exception {
        return createAndMockTxOutPoint(address, amount, null, null, null);
    }

    public TxOutPoint createAndMockTxOutPoint(SegwitAddress address, long amount, int nbConfirmations) throws Exception {
        return createAndMockTxOutPoint(address, amount, nbConfirmations, null, null);
    }

    public TxOutPoint createAndMockTxOutPoint(SegwitAddress address, long amount, Integer nbConfirmations, String utxoHash, Integer utxoIndex) throws Exception{
        // generate transaction with bitcoinj
        Transaction transaction = new Transaction(cryptoService.getNetworkParameters());

        if (nbConfirmations == null) {
            nbConfirmations = 1000;
        }

        if (utxoHash != null) {
            transaction = Mockito.spy(transaction);
            Mockito.doReturn(new Sha256Hash(Hex.decode(utxoHash))).when(transaction).getHash();
        }

        if (utxoIndex != null) {
            for (int i=0; i<utxoIndex; i++) {
                transaction.addOutput(Coin.valueOf(amount), createSegwitAddress().getAddress());
            }
        }
        String addressBech32 = address.getBech32AsString();
        TransactionOutput transactionOutput = bech32Util.getTransactionOutput(addressBech32, amount, cryptoService.getNetworkParameters());
        transaction.addOutput(transactionOutput);
        TransactionOutPoint outPoint = transactionOutput.getOutPointFor();

        // mock at rpc level
        RpcTransaction rpcTransaction = new RpcTransaction(transaction.getHashAsString(), nbConfirmations);
        RpcOut rpcOut = new RpcOut(outPoint.getIndex(), amount, outPoint.getConnectedPubKeyScript(), Arrays.asList(addressBech32));
        rpcTransaction.addRpcOut(rpcOut);
        ((TestBlockchainDataService)blockchainDataService).mock(rpcTransaction);

        TxOutPoint txOutPoint = new TxOutPoint(rpcTransaction.getHash(), rpcOut.getIndex(), rpcOut.getValue());
        return txOutPoint;
    }

}
