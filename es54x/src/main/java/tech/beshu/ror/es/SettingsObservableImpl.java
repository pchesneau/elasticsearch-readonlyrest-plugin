/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package tech.beshu.ror.es;

import cz.seznam.euphoria.shaded.guava.com.google.common.util.concurrent.FutureCallback;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import tech.beshu.ror.commons.SettingsForStorage;
import tech.beshu.ror.commons.SettingsObservable;
import tech.beshu.ror.commons.shims.es.LoggerShim;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static tech.beshu.ror.commons.Constants.SETTINGS_YAML_FILE;

/**
 * Created by sscarduzio on 25/06/2017.
 */

@Singleton
public class SettingsObservableImpl extends SettingsObservable {
  private static final LoggerShim logger = ESContextImpl.mkLoggerShim(Loggers.getLogger(SettingsObservableImpl.class));

  private final NodeClient client;
  private Settings settings;

  @Inject
  public SettingsObservableImpl(NodeClient client) {
    super();
    this.client = client;
    current = this.getFromFileWithFallbackToES();
  }

  @Override
  protected void writeToIndex(SettingsForStorage s4s, FutureCallback f) {
    String jsonToCommit = s4s.toJsonStorage();
    client.prepareBulk().add(
      client.prepareIndex(".readonlyrest", "settings", "1")
        .setSource(jsonToCommit, XContentType.JSON).request()
    ).execute().addListener(new ActionListener<BulkResponse>() {
      @Override
      public void onResponse(BulkResponse bulkItemResponses) {
        logger.info("all ok, written settings");
        f.onSuccess(bulkItemResponses);
      }

      @Override
      public void onFailure(Exception e) {
        logger.error("could not write settings to index: ", e);
        f.onFailure(e);
      }
    });
  }


  @Override
  protected LoggerShim getLogger() {
    return logger;
  }

  @Override
  protected SettingsForStorage getFromIndex() {
    GetResponse resp = null;
    try {
      resp = client.prepareGet(".readonlyrest", "settings", "1").get();
    } catch (Throwable t) {
      if (t instanceof ResourceNotFoundException) {
        throw new ElasticsearchException(SETTINGS_NOT_FOUND_MESSAGE);
      }
      throw new ElasticsearchException(t.getMessage());
    }
    if (resp == null || !resp.isExists()) {
      throw new ElasticsearchException(SETTINGS_NOT_FOUND_MESSAGE);
    }
    String yamlString = (String) resp.getSource().get("settings");
    return new SettingsForStorage(yamlString);
  }


  @Override
  public boolean isClusterReady() {
    try {
      ClusterHealthStatus status = client.admin().cluster().prepareHealth().get().getStatus();
      Boolean ready = !status.equals(ClusterHealthStatus.RED);
      return ready;
    } catch (Throwable e) {
      return false;
    }
  }

}
