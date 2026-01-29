package com.dripswap.bff.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class AddressBookTest {
    @Test
    void parsesSimpleKeyValues() throws Exception {
        Path p = Files.createTempFile("address_book", ".md");
        Files.writeString(p, """
            # comment
            tokens.vUSDC.address: 0xabc
            tokens.vUSDC.decimals: 6
            faucetv2.address: 0xdef
            """);

        AddressBook book = AddressBook.load(p);
        assertEquals("0xabc", book.getTokenAddressBySymbol("vUSDC"));
        assertEquals("0xdef", book.get("faucetv2.address"));
    }
}
