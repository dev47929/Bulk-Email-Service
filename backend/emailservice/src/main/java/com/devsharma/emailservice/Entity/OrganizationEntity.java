package com.devsharma.emailservice.Entity;

import com.devsharma.emailservice.Entity.Enum.Plans;
import jakarta.persistence.*;

@Entity
public class OrganizationEntity {
    private Long id;
    private boolean deleted;

    private String orgName;
    private String orgMail;
    private String orgAddress;

    @Enumerated(EnumType.STRING)
    private Plans plan;

}
