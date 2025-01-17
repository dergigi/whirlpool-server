package com.samourai.whirlpool.server.services;

import com.samourai.wallet.util.MessageSignUtilGeneric;
import com.samourai.whirlpool.protocol.fee.WhirlpoolFeeData;
import com.samourai.whirlpool.server.beans.Pool;
import com.samourai.whirlpool.server.beans.PoolFee;
import com.samourai.whirlpool.server.beans.rpc.RpcTransaction;
import com.samourai.whirlpool.server.beans.rpc.TxOutPoint;
import com.samourai.whirlpool.server.config.WhirlpoolServerConfig;
import com.samourai.whirlpool.server.exceptions.IllegalInputException;
import java.lang.invoke.MethodHandles;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Transaction;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class InputValidationService {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private FeeValidationService feeValidationService;
  private WhirlpoolServerConfig whirlpoolServerConfig;
  private CryptoService cryptoService;
  private MessageSignUtilGeneric messageSignUtil;

  public InputValidationService(
      FeeValidationService feeValidationService,
      WhirlpoolServerConfig whirlpoolServerConfig,
      CryptoService cryptoService,
      DbService dbService,
      MessageSignUtilGeneric messageSignUtil) {
    this.feeValidationService = feeValidationService;
    this.whirlpoolServerConfig = whirlpoolServerConfig;
    this.cryptoService = cryptoService;
    this.messageSignUtil = messageSignUtil;
  }

  public TxOutPoint validateProvenance(
      TxOutPoint txOutPoint,
      RpcTransaction tx,
      boolean liquidity,
      boolean testMode,
      Pool pool,
      boolean hasMixTxid)
      throws IllegalInputException {

    // provenance verification can be disabled with testMode
    if (whirlpoolServerConfig.isTestMode() && testMode) {
      log.warn("tx0 check disabled by testMode");
      return txOutPoint;
    }

    // verify input comes from a valid tx0 or previous mix
    boolean isLiquidity =
        checkInputProvenance(tx, txOutPoint.getValue(), pool.getPoolFee(), hasMixTxid);
    if (!isLiquidity && liquidity) {
      throw new IllegalInputException("Input rejected: joined as liquidity but is a mustMix");
    }
    if (isLiquidity && !liquidity) {
      throw new IllegalInputException("Input rejected: joined as mustMix but is as a liquidity");
    }
    return txOutPoint;
  }

  protected boolean checkInputProvenance(
      RpcTransaction rpcTx, long inputValue, PoolFee poolFee, boolean hasMixTxid)
      throws IllegalInputException {
    Transaction tx = rpcTx.getTx();
    // is it a tx0?
    WhirlpoolFeeData feeData = feeValidationService.decodeFeeData(tx);
    if (feeData != null) {
      // this is a tx0 => mustMix
      String feePayloadHex =
          feeData.getFeePayload() != null ? Hex.toHexString(feeData.getFeePayload()) : "null";
      if (log.isDebugEnabled()) {
        log.debug(
            "Validating input: txid="
                + tx.getHashAsString()
                + ", value="
                + inputValue
                + ": feeIndice="
                + feeData.getFeeIndice()
                + ", feePayloadHex="
                + feePayloadHex);
      }

      // check fees paid
      if (!feeValidationService.isValidTx0(rpcTx.getTx(), rpcTx.getTxTime(), feeData, poolFee)) {
        throw new IllegalInputException(
            "Input rejected (invalid fee for tx0="
                + tx.getHashAsString()
                + ", x="
                + feeData.getFeeIndice()
                + ", feePayloadHex="
                + feePayloadHex
                + ")");
      }
      return false; // mustMix
    } else {
      // this is not a valid tx0 => liquidity coming from a previous whirlpool tx
      if (log.isDebugEnabled()) {
        log.debug(
            "Validating input: txid="
                + tx.getHashAsString()
                + ", value="
                + inputValue
                + ": feeData=null");
      }

      if (!hasMixTxid) { // not a whirlpool tx
        throw new IllegalInputException("Input rejected (not a premix or whirlpool input)");
      }
      return true; // liquidity
    }
  }

  public ECKey validateSignature(TxOutPoint txOutPoint, String message, String signature)
      throws IllegalInputException {
    if (log.isDebugEnabled()) {
      log.debug(
          "Verifying signature: "
              + signature
              + "\n  for address: "
              + txOutPoint.getToAddress()
              + "\n  for message: "
              + message);
    }

    // verify signature of message for address
    if (!messageSignUtil.verifySignedMessage(
        txOutPoint.getToAddress(), message, signature, cryptoService.getNetworkParameters())) {
      throw new IllegalInputException("Invalid signature");
    }

    ECKey pubkey = messageSignUtil.signedMessageToKey(message, signature);
    if (pubkey == null) {
      throw new IllegalInputException("Invalid signature");
    }
    return pubkey;
  }
}
