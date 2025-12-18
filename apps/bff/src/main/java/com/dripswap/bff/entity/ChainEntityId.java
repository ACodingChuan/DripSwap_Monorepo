package com.dripswap.bff.entity;

import lombok.Data;

import java.io.Serializable;

@Data
public class ChainEntityId implements Serializable {

    private String id;
    private String chainId;
}

