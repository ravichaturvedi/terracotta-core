/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.management.service.impl;

import org.terracotta.management.ServiceExecutionException;

import com.tc.license.ProductID;
import com.terracotta.management.resource.ClientEntity;
import com.terracotta.management.resource.ServerGroupEntity;
import com.terracotta.management.service.TopologyService;
import com.terracotta.management.service.TsaManagementClientService;

import java.util.Collection;
import java.util.Set;

/**
 * @author Ludovic Orban
 */
public class TopologyServiceImpl implements TopologyService {

  private final TsaManagementClientService tsaManagementClientService;

  public TopologyServiceImpl(TsaManagementClientService tsaManagementClientService) {
    this.tsaManagementClientService = tsaManagementClientService;
  }

  @Override
  public Collection<ServerGroupEntity> getTopology() throws ServiceExecutionException {
    return tsaManagementClientService.getTopology();
  }

  @Override
  public Collection<ClientEntity> getClients(Set<ProductID> clientProductIds) throws ServiceExecutionException {
    return tsaManagementClientService.getClientEntities(clientProductIds);
  }

}
