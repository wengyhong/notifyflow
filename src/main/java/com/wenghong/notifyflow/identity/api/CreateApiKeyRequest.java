package com.wenghong.notifyflow.identity.api;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateApiKeyRequest(

    @NotBlank
    @Size (max = 100)
    String name) {

}
