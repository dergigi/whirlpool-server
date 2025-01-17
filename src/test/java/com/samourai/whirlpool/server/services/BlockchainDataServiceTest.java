package com.samourai.whirlpool.server.services;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.DEFINED_PORT;

import com.samourai.whirlpool.server.beans.rpc.RpcTransaction;
import com.samourai.whirlpool.server.integration.AbstractIntegrationTest;
import com.samourai.whirlpool.server.services.rpc.MockRpcClientServiceImpl;
import java.lang.invoke.MethodHandles;
import java.util.NoSuchElementException;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = DEFINED_PORT)
public class BlockchainDataServiceTest extends AbstractIntegrationTest {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Test
  public void getRpcTransaction_96cebec97115f59339a9053b6084aab5869adeefdbdbe974b74bfdbf3b8eaac3() {
    String txid = "96cebec97115f59339a9053b6084aab5869adeefdbdbe974b74bfdbf3b8eaac3";
    RpcTransaction tx =
        blockchainDataService
            .getRpcTransaction(txid)
            .orElseThrow(() -> new NoSuchElementException());
    Assert.assertEquals(MockRpcClientServiceImpl.MOCK_TX_CONFIRMATIONS, tx.getConfirmations());
    Assert.assertEquals(
        "96cebec97115f59339a9053b6084aab5869adeefdbdbe974b74bfdbf3b8eaac3",
        tx.getTx().getHashAsString());
    Assert.assertEquals(
        "010000000175c4a245884e82b740d7c2ad6bd122c8a14ee35d75561e8e125ce87e2b0cbdac010000006b483045022100cea6b68f65775d6f28bb6c592433517d5d5284045607160a1c554ca52b257b66022026b785d5080e9149e37f7e3ef5e5bf78d30d2512d066d416b300a4be6b1527c70121024ca44f264eb1c3cdbdb7108ae15f42969ce02bfe48bac94f9845535dcc33ac86ffffffff027dfd1e07000000001976a914f90fdb0b607a45715ed8326a2180f4d338b242d588ac69eecd25000000001976a914868c11afa0e8dbe159b3eacc08ff017fba9d391988ac00000000",
        Hex.toHexString(tx.getTx().bitcoinSerialize()));
  }

  @Test
  public void getRpcTransaction_7ea75da574ebabf8d17979615b059ab53aae3011926426204e730d164a0d0f16() {
    String txid = "7ea75da574ebabf8d17979615b059ab53aae3011926426204e730d164a0d0f16";
    RpcTransaction tx =
        blockchainDataService
            .getRpcTransaction(txid)
            .orElseThrow(() -> new NoSuchElementException());
    Assert.assertEquals(MockRpcClientServiceImpl.MOCK_TX_CONFIRMATIONS, tx.getConfirmations());
    Assert.assertEquals(
        "7ea75da574ebabf8d17979615b059ab53aae3011926426204e730d164a0d0f16",
        tx.getTx().getHashAsString());
    Assert.assertEquals(
        "010000000001033dfcfe7fb293d1b6f41b8894f896b3aaceb7f9c023061f533f7321def7929b41020000006b483045022100fd69af97109ff7f5b6aa656e8401d1f00d136ec2577d20b01b2f5154ef41f5420220205a62c372bec510caf800b2a996cc7bf0f52fc0d17fc871dd5c911bb495754501210206e398443b1468e028ef785281fdb39565d8f5dd5e29b9b8cf3fe6efb93062bafdffffff2b40dc90d245e3c23e1b39bdf17b5d1010919fd4f244c9878d4ccd217eef737c000000001716001485cafa3f554071a35f571027b8834b33b82ec056fdffffffc45432e67a0adad659f7249472756293717d423360b0c9849e6809759c03da84020000006a4730440220024e6febc89c6e313f8b297f1aec87ff057128c253f7e352b1635c3c88cf504002206c51f50d1dd4fa4c689c24d2c2bb35ee1d5cb2f99602e0aea9ffdc37092c062b012102632f214738f6f7708e201f6a299d6351eb87caf6b86ce94187ea39c98d18a60bfdffffff04e947e1020000000017a9148249408a629e70e42349addd3e36888a0ea1578287cacbf505000000001976a9143cff5d8af264dcbbc84bae87a209d3efce31734388ac1008f6050000000016001493045495bc69c0d6a3c9e5285c8969f23c79cf951008f60500000000160014d798ca9c7e764f5186887f0b381a50b7122c668b00024830450221009a870dec25f0794b91e594f21a88ea68e9ae9eb8824e54ce2cedd9c9ebe2ed7202203de75af50fe318738ca0e189835b66a5bc3f392d4a307a8c2cd29f780b19e57801210376edd2a70c6eba6b32f35965db0ed9c5502c0876b0600e754f9da9511ab80bca0000000000",
        Hex.toHexString(tx.getTx().bitcoinSerialize()));
  }
}
