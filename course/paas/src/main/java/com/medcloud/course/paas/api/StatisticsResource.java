package com.medcloud.course.paas.api;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.statistics.StatisticsService;
import com.medcloud.course.paas.auth.*;
import javax.ejb.EJB;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

@Path("platform/statistics")
@Produces(MediaType.APPLICATION_JSON)
public class StatisticsResource {
    @EJB private AuthService auth;
    @EJB private StatisticsService statistics;

    public StatisticsResource() { }
    public StatisticsResource(AuthService auth, StatisticsService statistics) { this.auth = auth; this.statistics = statistics; }

    @GET
    public ApiResponse<StatisticsReport> platform(@HeaderParam("Authorization") String authorization,
            @QueryParam("from") String from, @QueryParam("to") String to) {
        return ApiResponse.success("platform statistics",
                statistics.platform(auth.authenticate(BearerTokens.require(authorization)), from, to));
    }
}
