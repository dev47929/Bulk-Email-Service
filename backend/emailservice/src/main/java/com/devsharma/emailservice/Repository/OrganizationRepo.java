package com.devsharma.emailservice.Repository;

import com.devsharma.emailservice.Entity.OrganizationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepo extends JpaRepository<OrganizationEntity , Long> {
}
