/*
 * @(#)TenantResource        1.6 11/09/07
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.resources.v1;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.midokura.midolman.mgmt.data.Tenant;
import com.midokura.midolman.mgmt.data.TenantDataAccessor;

/**
 * Resource class for tenants.
 *
 * @version        1.6 07 Sept 2011
 * @author         Ryu Ishimoto
 */
@Path("/v1/tenants")
public class TenantResource extends RestResource {
    /*
     * Implements REST API endpoints for tenants.
     */

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Tenant getTenant(@PathParam("id") UUID id) {
        // Get Tenant object for a given ID.
        TenantDataAccessor dao = new TenantDataAccessor(zookeeperConn);
        Tenant tenant = null;
        try {
            tenant = dao.find(id);
        } catch (Exception ex) {
            // TODO: LOG
            System.err.println("Exception = " + ex.getMessage());
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON).build());
        }
        return tenant;
    }
    
    /**
     * Handler for create tenant API call.
     * 
     * @param   is  InputStream of the request.
     * @returns  Response object with 201 status code set if successful.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createTenant(Tenant tenant) {
        // Add a new tenant entry into zookeeper.
        if(tenant.getId() == null) {
            tenant.setId(UUID.randomUUID());
        }

        TenantDataAccessor dao = new TenantDataAccessor(zookeeperConn);
        try {
            dao.create(tenant);
        } catch (Exception ex) {
            // TODO: LOG
            System.err.println("Exception = " + ex.getMessage());
            throw new WebApplicationException(
                    Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.APPLICATION_JSON).build());
        }
        
        return Response.created(URI.create("/v1/tenant/"
                + tenant.getId())).build();
    }    
}
