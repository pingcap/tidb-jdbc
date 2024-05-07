/*
 * Copyright 2021 TiDB Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tidb.jdbc.impl;

import static java.lang.String.format;

import com.tidb.jdbc.Discoverer;
import com.tidb.jdbc.ExceptionHelper;
import com.tidb.jdbc.conf.ConnUrlParser;
import com.tidb.jdbc.utils.RandomUtils;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class DiscovererImpl implements Discoverer {

  private static final String QUERY_TIDB_SERVER_SQL =
      "SELECT `IP`,`PORT` FROM `INFORMATION_SCHEMA`.`TIDB_SERVERS_INFO` ORDER BY `IP`";
  private static final String MYSQL_URL_PREFIX_REGEX = "jdbc:mysql://[^/]+:\\d+";

  private static final String TIDB_URL_MAPPER = "tidb.jdbc.url-mapper";

  private static final String TIDB_DISCOVERY = "tidb.discovery";

  private final String[] bootstrapUrl;
  private final Properties info;
  private final Driver driver;
  private final AtomicBoolean discovering = new AtomicBoolean(false);
  private final AtomicLong lastReloadTime = new AtomicLong();
  private final AtomicReference<String[]> backends = new AtomicReference<>();
  private final ConcurrentHashMap<String, String> failedBackends = new ConcurrentHashMap<>();
  private final Executor executor;

  private Map<String,Integer> weightBankend = new ConcurrentHashMap<>();

  public DiscovererImpl(
      final Driver driver,
      final String bootstrapUrl,
      final Properties info,
      final Executor executor) {
    this.driver = driver;
    this.bootstrapUrl = new String[] {bootstrapUrl};
    this.info = info != null ? (Properties) info.clone() : null;
    this.backends.set(this.bootstrapUrl);
    this.executor = executor;
    discover(info);
  }

  private String[] getValidBackends() {
    String[] result = backends.get();

    if (result == bootstrapUrl || failedBackends.isEmpty()) {
      return result;
    }
    result =
        Arrays.stream(result).filter((b) -> !failedBackends.containsKey(b)).toArray(String[]::new);
    if (result.length == 0) {
      result = bootstrapUrl;
    }
    return result;
  }

  @Override
  public String[] getAndReload() {final Future<String[]> future = reload();
    if (future.isDone()) {
      return ExceptionHelper.uncheckedCall(future::get);
    }
    return getValidBackends();
  }

  @Override
  public String[] get() {
    return getValidBackends();
  }

  @Override
  public Future<String[]> reload() {
    if (!discovering.compareAndSet(false, true)) {
      return CompletableFuture.completedFuture(getValidBackends());
    } else {
      return CompletableFuture.supplyAsync(() -> discover(info), executor);
    }
  }

  @Override
  public long getLastReloadTime() {
    return lastReloadTime.get();
  }

  @Override
  public void succeeded(final String backend) {
    if (backend.equals(bootstrapUrl[0])) {
      return;
    }
    failedBackends.remove(backend);
  }

  @Override
  public void failed(final String backend) {
    if (backend.equals(bootstrapUrl[0])) {
      return;
    }
    failedBackends.put(backend, backend);
  }

  private String[] discover(final Properties info) {
    ExceptionHelper<String[]> result = null;
    try {
      String finalTry = bootstrapUrl[0];
//      for (String tidbUrl : backends.get()) {
//        if (failedBackends.containsKey(tidbUrl)) {
//          continue;
//        }
//        result = discover(tidbUrl, info);
//        if (result.isOk()) {
//          return result.unwrap();
//        }
//        if (tidbUrl.equals(finalTry)) {
//          finalTry = null;
//        }
//      }
      int backendsSize = backends.get().length;
      String[] urls = backends.get();
      for (int i=0;i<backendsSize;i++){
        String tidbUrl = urls[RandomUtils.randomValue(backendsSize)];
        if (failedBackends.containsKey(tidbUrl)) {
          continue;
        }
        result = discover(tidbUrl, info);
        if (result.isOk()) {
          return result.unwrap();
        }
        if (tidbUrl.equals(finalTry)) {
          finalTry = null;
        }
      }

      if (finalTry != null) {
        result = discover(finalTry, info);
        if (result.isOk()) {
          return result.unwrap();
        }
      }
      assert result != null;
      throw new RuntimeException("No available endpoint to discover backends", result.unwrapErr());
    } finally {
      if (result != null && result.isOk()) {

        backends.set(result.unwrap());
        failedBackends.clear();
      }
      lastReloadTime.set(System.currentTimeMillis());
      discovering.set(false);
    }
  }

  private ExceptionHelper<String[]> discover(final String tidbUrl, final Properties info) {
    return ExceptionHelper.call(
        () -> {
          ConnUrlParser connStrParser = ConnUrlParser.parseConnectionString(tidbUrl);
          Properties properties = connStrParser.getConnectionArgumentsAsProperties();
          if(properties != null){
            String provider = properties.getProperty(TIDB_URL_MAPPER);
            String isDiscoverer = properties.getProperty(TIDB_DISCOVERY);
            if(provider != null && "weight".equals(provider) && isDiscoverer != null && "false".equals(isDiscoverer)){
              return new String[0];
            }
          }
          try (final Connection connection = driver.connect(tidbUrl, info);
              final Statement statement = connection.createStatement();
              final ResultSet resultSet = statement.executeQuery(QUERY_TIDB_SERVER_SQL)) {
            final List<String> list = new ArrayList<>();
            while (resultSet.next()) {
              final String ip = resultSet.getString("IP");
              final int port = resultSet.getInt("PORT");
              list.add(
                  bootstrapUrl[0].replaceFirst(
                      MYSQL_URL_PREFIX_REGEX, format("jdbc:mysql://%s:%d", ip, port)));
            }
            return list.toArray(new String[0]);
          }

        });
  }
}
