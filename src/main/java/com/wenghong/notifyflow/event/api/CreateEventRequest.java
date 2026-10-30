package com.wenghong.notifyflow.event.api;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record CreateEventRequest(

        @NotBlank @Size (max = 100) String type,

        @NotNull JsonNode payload) {

}
