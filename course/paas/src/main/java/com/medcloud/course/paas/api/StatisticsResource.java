package com.medcloud.course.paas.api;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.statistics.StatisticsService;
import com.medcloud.course.paas.auth.*;
import javax.ejb.EJB;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class StatisticsResource {
    @EJB private AuthService auth;
    @EJB private StatisticsService statistics;

    public StatisticsResource() { }
    public StatisticsResource(AuthService auth, StatisticsService statistics) { this.auth = auth; this.statistics = statistics; }

    @GET @Path("hospital/statistics")
    public ApiResponse<StatisticsReport> hospital(@HeaderParam("Authorization") String authorization,
            @QueryParam("from") String from, @QueryParam("to") String to, @Context UriInfo uri) {
        CurrentActor actor = auth.authenticate(BearerTokens.require(authorization));
        if (uri.getQueryParameters().containsKey("tenantId"))
            throw new IllegalArgumentException("hospital statistics does not accept tenantId");
        return ApiResponse.success("hospital statistics", statistics.hospital(actor, from, to));
    }

    @GET @Path("platform/statistics")
    public ApiResponse<StatisticsReport> platform(@HeaderParam("Authorization") String authorization,
            @QueryParam("from") String from, @QueryParam("to") String to) {
        return ApiResponse.success("platform statistics",
                statistics.platform(auth.authenticate(BearerTokens.require(authorization)), from, to));
    }
}
