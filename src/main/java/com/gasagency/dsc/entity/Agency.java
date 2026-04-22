package com.gasagency.dsc.entity;

import com.gasagency.dsc.enums.PlanType;
import com.gasagency.dsc.enums.TelephonyProvider;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "agencies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Agency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    private String phone;

    private String city;

    private String address;

    @Builder.Default
    @Column(nullable = false)
    private String role = "AGENCY";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false)
    private PlanType planType = PlanType.FREE;

    @Column(name = "elevenlabs_agent_id")
    private String elevenLabsAgentId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "telephony_provider")
    private TelephonyProvider telephonyProvider = TelephonyProvider.TWILIO;

    @Column(name = "twilio_phone_number")
    private String twilioPhoneNumber;

    @Column(name = "transfer_number")
    private String transferNumber;

    @Builder.Default
    @Column(name = "agent_name")
    private String agentName = "Raju";

    @Builder.Default
    @Column(name = "monthly_call_limit")
    private Integer monthlyCallLimit = 100;

    @Builder.Default
    @Column(name = "calls_used_this_month")
    private Integer callsUsedThisMonth = 0;

    @Builder.Default
    @Column(name = "setup_completed")
    private Boolean setupCompleted = false;

    @Builder.Default
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
