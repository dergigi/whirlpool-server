package com.samourai.whirlpool.server.services;

import com.samourai.wallet.api.backend.BackendApi;
import com.samourai.whirlpool.server.beans.BackendServer;
import com.samourai.whirlpool.server.config.WhirlpoolServerConfig;
import org.springframework.stereotype.Service;

@Service
public class BackendService extends BackendApi {

  public BackendService(
      JavaHttpClientService httpClientService, WhirlpoolServerConfig serverConfig) {
    super(
        httpClientService,
        serverConfig.isTestnet()
            ? BackendServer.TESTNET.getBackendUrlClear()
            : BackendServer.MAINNET.getBackendUrlClear());
  }
}
