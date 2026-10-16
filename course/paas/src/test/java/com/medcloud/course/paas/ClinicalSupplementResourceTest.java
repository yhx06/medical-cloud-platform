package com.medcloud.course.paas;

import com.medcloud.course.contract.*;
import com.medcloud.course.paas.api.ApiFailureMapper;
import com.medcloud.course.paas.audit.AuditedOperationException;
import com.medcloud.course.paas.consultation.ClinicalSupplementException;
import org.junit.jupiter.api.Test;
import javax.ejb.EJBException;
import javax.ws.rs.core.Response;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ClinicalSupplementResourceTest {
    @Test void closedClinicalErrorsPreserveStatusCodeAndAuditReasonThroughWrappers() {
        String[] codes={"CLINICAL_INPUT_INVALID","CLINICAL_STATE_CONFLICT","CLINICAL_REVISION_CONFLICT","CLINICAL_OPERATION_CONFLICT"};
        for(int i=0;i<codes.length;i++) {
            int status=i==0?400:409;String id=UUID.randomUUID().toString();
            RuntimeException error=new EJBException(new AuditedOperationException(new ClinicalSupplementException(status,codes[i]),id));
            Response response=new ApiFailureMapper().toResponse(error);assertEquals(status,response.getStatus());
            ApiError body=(ApiError)response.getEntity();assertEquals(codes[i],body.getCode());assertEquals(id,body.getRequestId());
            assertEquals(i==0?AuditReasonCode.BAD_REQUEST:AuditReasonCode.CONFLICT,ApiFailureMapper.reasonFor(error));
        }
    }
}
