package com.samourai.whirlpool.server.utils;

import com.samourai.wallet.bip47.rpc.BIP47Wallet;
import com.samourai.wallet.hd.HD_Wallet;
import com.samourai.wallet.hd.java.HD_WalletFactoryJava;
import com.samourai.wallet.segwit.SegwitAddress;
import com.samourai.wallet.segwit.bech32.Bech32UtilGeneric;
import com.samourai.wallet.util.CryptoTestUtil;
import com.samourai.whirlpool.server.beans.ConfirmedInput;
import com.samourai.whirlpool.server.beans.Mix;
import com.samourai.whirlpool.server.beans.Pool;
import com.samourai.whirlpool.server.beans.RegisteredInput;
import com.samourai.whirlpool.server.beans.rpc.TxOutPoint;
import com.samourai.whirlpool.server.services.CryptoService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.aspectj.util.FileUtil;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.bouncycastle.util.io.pem.PemWriter;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TestUtils {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private CryptoService cryptoService;
  protected Bech32UtilGeneric bech32Util;
  protected HD_WalletFactoryJava hdWalletFactory;
  private CryptoTestUtil cryptoTestUtil;

  public TestUtils(
      CryptoService cryptoService,
      Bech32UtilGeneric bech32Util,
      HD_WalletFactoryJava hdWalletFactory,
      CryptoTestUtil cryptoTestUtil) {
    this.cryptoService = cryptoService;
    this.bech32Util = bech32Util;
    this.hdWalletFactory = hdWalletFactory;
    this.cryptoTestUtil = cryptoTestUtil;
  }

  public SegwitAddress generateSegwitAddress() {
    return cryptoTestUtil.generateSegwitAddress(cryptoService.getNetworkParameters());
  }

  public BIP47WalletAndHDWallet generateWallet(byte[] seed, String passphrase) throws Exception {
    // init BIP44 wallet
    HD_Wallet inputWallet =
        hdWalletFactory.getHD(44, seed, passphrase, cryptoService.getNetworkParameters());

    // init BIP47 wallet
    BIP47Wallet bip47InputWallet = new BIP47Wallet(47, inputWallet, 1);

    return new BIP47WalletAndHDWallet(bip47InputWallet, inputWallet);
  }

  public BIP47WalletAndHDWallet generateWallet() throws Exception {
    byte seed[] = cryptoTestUtil.generateSeed();
    return generateWallet(seed, "test");
  }

  private String getMockFileName(String txid) {
    return "./src/test/resources/mocks/" + txid + ".txt";
  }

  public void writeMockRpc(String txid, String rawTxHex) throws Exception {
    String fileName = getMockFileName(txid);
    System.out.println("writing " + fileName + ": " + rawTxHex);
    Files.write(Paths.get(fileName), rawTxHex.getBytes(), StandardOpenOption.CREATE);
  }

  public Optional<String> loadMockRpc(String txid) {
    String mockFile = getMockFileName(txid);
    try {
      log.info("reading mock: " + mockFile);
      String rawTx = FileUtil.readAsString(new File(mockFile));
      return Optional.of(rawTx);
    } catch (Exception e) {
      log.info("mock not found: " + mockFile);
      return Optional.empty();
    }
  }

  public void assertPool(int nbMustMix, int nbLiquidity, Pool pool) {
    Assert.assertEquals(nbMustMix, pool.getMustMixQueue().getSize());
    Assert.assertEquals(nbLiquidity, pool.getLiquidityQueue().getSize());
  }

  public void assertPoolEmpty(Pool pool) {
    assertPool(0, 0, pool);
  }

  public void assertMix(int nbInputsConfirmed, int confirming, Mix mix) {
    Assert.assertEquals(nbInputsConfirmed, mix.getNbInputs());
    Assert.assertEquals(confirming, mix.getNbConfirmingInputs());
  }

  public void assertMix(int nbInputs, Mix mix) {
    assertMix(nbInputs, 0, mix);
  }

  public void assertMixEmpty(Mix mix) {
    assertMix(0, mix);
  }

  public AsymmetricCipherKeyPair readPkPEM(String pkPem) throws Exception {
    PemReader pemReader =
        new PemReader(new InputStreamReader(new ByteArrayInputStream(pkPem.getBytes())));
    PemObject pemObject = pemReader.readPemObject();

    RSAPrivateCrtKeyParameters privateKeyParams =
        (RSAPrivateCrtKeyParameters) PrivateKeyFactory.createKey(pemObject.getContent());
    return new AsymmetricCipherKeyPair(privateKeyParams, privateKeyParams); // TODO
  }

  public String computePkPEM(AsymmetricCipherKeyPair keyPair) throws Exception {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    PemWriter writer = new PemWriter(new OutputStreamWriter(os));

    PrivateKeyInfo pkInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(keyPair.getPrivate());

    writer.writeObject(new PemObject("PRIVATE KEY", pkInfo.getEncoded()));
    writer.flush();
    writer.close();
    String pem = new String(os.toByteArray());
    return pem;
  }

  public ConfirmedInput computeConfirmedInput(String utxoHash, long utxoIndex, boolean liquidity) {
    TxOutPoint outPoint = new TxOutPoint(utxoHash, utxoIndex, 1234, 99, null, "fakeReceiveAddress");
    RegisteredInput registeredInput = new RegisteredInput("foo", liquidity, outPoint, "127.0.0.1");
    ConfirmedInput confirmedInput = new ConfirmedInput(registeredInput, null);
    return confirmedInput;
  }
}
