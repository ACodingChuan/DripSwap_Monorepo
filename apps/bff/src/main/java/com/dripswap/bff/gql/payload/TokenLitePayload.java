package com.dripswap.bff.gql.payload;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class TokenLitePayload {
    String address;
    String symbol;
    String name;
}

