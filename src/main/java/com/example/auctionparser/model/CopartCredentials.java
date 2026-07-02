package com.example.auctionparser.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Copart account credentials used by the headless-browser login. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CopartCredentials {

    private String username;
    private String password;

    public boolean isConfigured() {
        return username != null && !username.isBlank()
                && password != null && !password.isBlank();
    }
}
