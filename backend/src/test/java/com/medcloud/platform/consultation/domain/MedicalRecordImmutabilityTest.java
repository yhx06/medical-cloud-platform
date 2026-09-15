package com.medcloud.platform.consultation.domain;

import com.medcloud.platform.appointment.domain.Appointment;
import com.medcloud.platform.common.error.BusinessException;
import com.medcloud.platform.common.error.ErrorCode;
import com.medcloud.platform.schedule.domain.ScheduleSlot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MedicalRecordImmutabilityTest {

    @Test
    void completingARecordFreezesItsOriginalTextAndPrescription() {
        Consultation consultation = startedConsultation();
        consultation.saveDraft(
                "反复头痛两天",
                "偏头痛待观察",
                "规律休息并复诊",
                List.of(new PrescriptionItem.Draft("布洛芬", "0.2g", "每日两次", 3)));
        consultation.complete();

        assertThatThrownBy(() -> consultation.saveDraft(
                "被覆盖的主诉",
                "被覆盖的诊断",
                "被覆盖的建议",
                List.of(new PrescriptionItem.Draft("其他药品", "1片", "每日一次", 7))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.MEDICAL_RECORD_COMPLETED));

        assertThat(consultation.getComplaint()).isEqualTo("反复头痛两天");
        assertThat(consultation.getDiagnosis()).isEqualTo("偏头痛待观察");
        assertThat(consultation.getAdvice()).isEqualTo("规律休息并复诊");
        assertThat(consultation.getPrescriptionItems())
                .extracting(PrescriptionItem::getMedicationName)
                .containsExactly("布洛芬");
        assertThatThrownBy(() -> consultation.getPrescriptionItems().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void completingAnIncompleteDraftLeavesTheConsultationEditable() {
        Consultation consultation = startedConsultation();
        consultation.saveDraft(
                "反复头痛两天",
                "  ",
                "规律休息并复诊",
                List.of());

        assertThatThrownBy(consultation::complete)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        assertThat(consultation.getStatus()).isEqualTo(ConsultationStatus.IN_PROGRESS);
        assertThat(consultation.getCompletedAt()).isNull();
        consultation.saveDraft("反复头痛两天", "偏头痛", "规律休息并复诊", List.of());
        consultation.complete();
        assertThat(consultation.getStatus()).isEqualTo(ConsultationStatus.COMPLETED);
    }

    @Test
    void correctionsOnlyAppendAfterCompletionAndRetainSequenceAuthorAndReason() {
        Consultation consultation = startedConsultation();

        assertThatThrownBy(() -> consultation.appendCorrection(UUID.randomUUID(), "补充过敏史"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.APPOINTMENT_STATE_INVALID));

        consultation.saveDraft("头痛", "偏头痛", "观察", List.of());
        consultation.complete();
        UUID doctorId = consultation.getDoctorId();
        consultation.appendCorrection(doctorId, "补充青霉素过敏史");
        consultation.appendCorrection(doctorId, "复诊后补充血压正常");

        assertThat(consultation.getCorrections())
                .extracting(RecordCorrection::getSequenceNumber)
                .containsExactly(1, 2);
        assertThat(consultation.getCorrections())
                .extracting(RecordCorrection::getDoctorId)
                .containsOnly(doctorId);
        assertThat(consultation.getCorrections())
                .extracting(RecordCorrection::getReason)
                .containsExactly("补充青霉素过敏史", "复诊后补充血压正常");
        assertThat(consultation.getCorrections())
                .allSatisfy(correction -> assertThat(correction.getCreatedAt()).isNotNull());
    }

    private Consultation startedConsultation() {
        UUID tenantId = UUID.randomUUID();
        UUID doctorId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        ScheduleSlot slot = ScheduleSlot.publish(
                tenantId,
                doctorId,
                departmentId,
                LocalDate.now().plusDays(1),
                LocalTime.of(9, 0),
                LocalTime.of(10, 0),
                1,
                new BigDecimal("39.00"),
                LocalDate.now());
        Appointment appointment = Appointment.book(UUID.randomUUID(), slot);
        appointment.startConsultation();
        return Consultation.start(appointment);
    }
}
