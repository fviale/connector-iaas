/*
 * ProActive Parallel Suite(TM):
 * The Open Source library for parallel and distributed
 * Workflows & Scheduling, Orchestration, Cloud Automation
 * and Big Data Analysis on Enterprise Grids & Clouds.
 *
 * Copyright (c) 2007 - 2017 ActiveEon
 * Contact: contact@activeeon.com
 *
 * This library is free software: you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation: version 3 of
 * the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * If needed, contact us to obtain a release under GPL Version 2 or 3
 * or a different license than the AGPL.
 */
package org.ow2.proactive.connector.iaas.rest;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.ow2.proactive.connector.iaas.model.Instance;
import org.ow2.proactive.connector.iaas.service.InstanceService;
import org.ow2.proactive.connector.iaas.util.JacksonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


@Path("/infrastructures")
@Component
public class InstanceRest {

    @Autowired
    private InstanceService instanceService;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("application/json")
    @Path("{infrastructureId}/instances")
    public Response createInstance(@PathParam("infrastructureId") String infrastructureId, final String instanceJson) {
        Instance instance = JacksonUtil.convertFromJson(instanceJson, Instance.class);
        return Response.ok(instanceService.createInstance(infrastructureId, instance)).build();
    }

    @GET
    @Path("{infrastructureId}/instances")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getInstances(@PathParam("infrastructureId") String infrastructureId,
            @QueryParam("instanceId") String instanceId, @QueryParam("instanceTag") String instanceTag,
            @QueryParam("allInstances") Boolean allInstances) {

        if (Optional.ofNullable(instanceId).isPresent()) {
            return Response.ok(instanceService.getInstanceById(infrastructureId, instanceId)).build();
        } else if (Optional.ofNullable(instanceTag).isPresent()) {
            return Response.ok(instanceService.getInstanceByTag(infrastructureId, instanceTag)).build();
        } else if (Optional.ofNullable(allInstances).isPresent() && allInstances) {
            return Response.ok(instanceService.getAllInstances(infrastructureId)).build();
        } else {
            return Response.ok(instanceService.getCreatedInstances(infrastructureId)).build();
        }
    }

    @DELETE
    @Path("{infrastructureId}/instances")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteInstance(@PathParam("infrastructureId") String infrastructureId,
            @QueryParam("instanceId") String instanceId, @QueryParam("instanceTag") String instanceTag,
            @QueryParam("allCreatedInstances") Boolean allCreatedInstances) {

        if (Optional.ofNullable(instanceId).isPresent()) {
            instanceService.deleteInstance(infrastructureId, instanceId);
        } else if (Optional.ofNullable(instanceTag).isPresent()) {
            instanceService.deleteInstanceByTag(infrastructureId, instanceTag);
        } else if (Optional.ofNullable(allCreatedInstances).isPresent() && allCreatedInstances) {
            instanceService.deleteCreatedInstances(infrastructureId);
        }

        return Response.ok().build();
    }

    @POST
    @Path("{infrastructureId}/instances/publicIp")
    @Produces(MediaType.APPLICATION_JSON)
    public Response createPublicIp(@PathParam("infrastructureId") String infrastructureId,
            @QueryParam("instanceId") String instanceId, @QueryParam("instanceTag") String instanceTag,
            @QueryParam("desiredIp") String optionalDesiredIp) {
        Map<String, String> response = new HashMap<String, String>();
        if (Optional.ofNullable(instanceId).isPresent()) {
            response.put("publicIp",
                         instanceService.addToInstancePublicIp(infrastructureId, instanceId, optionalDesiredIp));
        } else if (Optional.ofNullable(instanceTag).isPresent()) {
            instanceService.addInstancePublicIpByTag(infrastructureId, instanceTag, optionalDesiredIp);
        } else {
            throw new ClientErrorException("The parameter \"instanceId\" and \"instanceTag\" are  missing.",
                                           Response.Status.BAD_REQUEST);
        }
        return Response.ok(response).build();
    }

    @DELETE
    @Path("{infrastructureId}/instances/publicIp")
    @Produces(MediaType.APPLICATION_JSON)
    public Response removePublicIp(@PathParam("infrastructureId") String infrastructureId,
            @QueryParam("instanceId") String instanceId, @QueryParam("instanceTag") String instanceTag,
            @QueryParam("desiredIp") String optionalDesiredIp) {

        if (Optional.ofNullable(instanceId).isPresent()) {
            instanceService.removeInstancePublicIp(infrastructureId, instanceId, optionalDesiredIp);
        } else if (Optional.ofNullable(instanceTag).isPresent()) {
            instanceService.removeInstancePublicIpByTag(infrastructureId, instanceTag, optionalDesiredIp);
        } else {
            throw new ClientErrorException("The parameters \"instanceId\" and \"instanceTag\" are missing.",
                                           Response.Status.BAD_REQUEST);
        }

        return Response.ok().build();
    }

}
