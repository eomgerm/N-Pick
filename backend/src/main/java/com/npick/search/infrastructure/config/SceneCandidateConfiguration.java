package com.npick.search.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 단어 검색 실행 설정을 빈으로 올린다. 값이 규약을 어기면 부팅이 실패한다 — 잘못된 가중치로 검색이 조용히 0건 나는 것보다 낫다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SceneCandidateProperties.class)
public class SceneCandidateConfiguration {}
