package com.ll.demo03.domain.member.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.*;
import lombok.experimental.SuperBuilder;
import com.ll.demo03.global.base.BaseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import com.ll.demo03.domain.member.entity.AuthProvider;

@Getter
@Setter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder
public class Member extends BaseEntity {
    private String name; //이름
    private String account; //아이디
    private String email; //이메일
    private String profile; //프로필 사진

    @Builder.Default
    private int credit = 5; //토큰 개수

    @Enumerated(EnumType.STRING)
    private Role role;

    @Enumerated(EnumType.STRING)
    private AuthProvider provider;

    private String providerId;

}
