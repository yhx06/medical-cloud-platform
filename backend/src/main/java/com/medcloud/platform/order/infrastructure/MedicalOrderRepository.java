package com.medcloud.platform.order.infrastructure;

import com.medcloud.platform.order.domain.MedicalOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MedicalOrderRepository extends JpaRepository<MedicalOrder, UUID> {
    Optional<MedicalOrder> findByConsultationId(UUID consultationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select medicalOrder from MedicalOrder medicalOrder where medicalOrder.id = :id")
    Optional<MedicalOrder> findByIdForUpdate(@Param("id") UUID id);

    List<MedicalOrder> findAllByPatientIdOrderByCreatedAtDesc(UUID patientId);

    Optional<MedicalOrder> findByIdAndPatientId(UUID id, UUID patientId);
}
