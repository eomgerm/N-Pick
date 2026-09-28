package com.npick.search.infrastructure.ai.client;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import com.npick.search.infrastructure.ai.client.request.TokenizeApiRequest;
import com.npick.search.infrastructure.ai.client.response.TokenizeApiResponse;

/** 워커의 토큰화 창구. 계약은 {@code docs/contracts/resolver-api.md} §2 다. */
@HttpExchange
public interface QueryTokenizerClient {

    @PostExchange("/query/tokenize")
    TokenizeApiResponse tokenize(@RequestBody TokenizeApiRequest request);
}
