package com.wenghong.notifyflow.endpoint.api;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wenghong.notifyflow.endpoint.application.InvalidWebhookUrlException;
import com.wenghong.notifyflow.endpoint.application.WebhookEndpointQueryService;
import com.wenghong.notifyflow.endpoint.application.WebhookEndpointService;
import com.wenghong.notifyflow.endpoint.application.WebhookEndpointSnapshot;
import com.wenghong.notifyflow.endpoint.domain.WebhookEndpoint;
import com.wenghong.notifyflow.identity.application.TenantAccess;
import com.wenghong.notifyflow.identity.application.TenantAccessService;
import com.wenghong.notifyflow.identity.security.AppUserDetails;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;

@RestController
@RequestMapping("/api/endpoints")
public class WebhookEndpointController {

        private final WebhookEndpointService service;
        private final WebhookEndpointQueryService queryService;
        private final TenantAccessService tenantAccessService;

        public WebhookEndpointController(
                        WebhookEndpointService service,
                        WebhookEndpointQueryService query,
                        TenantAccessService tenantAccessService) {
                this.service = service;
                this.queryService = query;
                this.tenantAccessService = tenantAccessService;
        }

        @PostMapping()
        @ResponseStatus(HttpStatus.CREATED)
        public WebhookEndpointCreatedResponse create(

                        @AuthenticationPrincipal AppUserDetails user,
                        @RequestHeader("X-Tenant-ID") UUID tenantId,
                        @RequestBody @Valid CreateWebhookEndpointRequest request) {

                TenantAccess access = tenantAccessService.requireAccess(tenantId, user.getUserId());

                WebhookEndpoint point = service.create(access, request.name(), request.url());

                return new WebhookEndpointCreatedResponse(
                                point.getId(),
                                point.getName(),
                                point.getUrl(),
                                point.isEnabled(),
                                point.getCreatedAt(),
                                point.getUpdatedAt(),
                                point.getSigningSecret());

        }

        @GetMapping("/{id}")
        public ResponseEntity<WebhookEndpointResponse> findById(
                        @AuthenticationPrincipal AppUserDetails user,
                        @RequestHeader("X-Tenant-ID") UUID tenantId,
                        @PathVariable UUID id) {

                TenantAccess access = tenantAccessService.requireAccess(tenantId, user.getUserId());

                return queryService.findById(access, id)
                                .map(this::toResponse)
                                .map(ResponseEntity::ok)
                                .orElseGet(() -> ResponseEntity.notFound().build());
        }

        @GetMapping
        public WebhookEndpointPageResponse findAll(
                        @AuthenticationPrincipal AppUserDetails user,
                        @RequestHeader("X-Tenant-ID") UUID tenantId,
                        @PageableDefault(size = 20, sort = { "createdAt",
                                        "id" }, direction = Sort.Direction.DESC) Pageable pageable) {

                TenantAccess access = tenantAccessService.requireAccess(tenantId, user.getUserId());
                Page<WebhookEndpointResponse> page = service.findAll(access, pageable).map(this::toResponse);

                return toPageResponse(page);
        }

        @PatchMapping("/{id}/enabled")
        public ResponseEntity<WebhookEndpointResponse> changeEnabled(
                        @AuthenticationPrincipal AppUserDetails user,
                        @RequestHeader("X-Tenant-ID") UUID tenantId,
                        @PathVariable UUID id,
                        @Valid @RequestBody ChangeWebhookEndpointEnabledRequest request) {

                TenantAccess access = tenantAccessService.requireAccess(tenantId, user.getUserId());

                return service.changeEnabled(access, id, request.enabled()).map(this::toResponse)
                                .map(ResponseEntity::ok)
                                .orElseGet(() -> ResponseEntity.notFound().build());
        }

        private WebhookEndpointResponse toResponse(WebhookEndpoint endpoint) {
                return new WebhookEndpointResponse(endpoint.getId(), endpoint.getName(), endpoint.getUrl(),
                                endpoint.isEnabled(), endpoint.getCreatedAt(), endpoint.getUpdatedAt());
        }

        private WebhookEndpointPageResponse toPageResponse(Page<WebhookEndpointResponse> page) {
                return new WebhookEndpointPageResponse(page.getContent(), page.getNumber(), page.getSize(),
                                page.getTotalElements(), page.getTotalPages());
        }

        @ExceptionHandler(InvalidWebhookUrlException.class)
        public ProblemDetail handleInvalidWebhookUrl(
                        InvalidWebhookUrlException exception) {
                return ProblemDetail.forStatusAndDetail(
                                HttpStatus.BAD_REQUEST,
                                exception.getMessage());
        }

        private WebhookEndpointResponse toResponse(
                        WebhookEndpointSnapshot snapshot) {
                return new WebhookEndpointResponse(
                                snapshot.id(),
                                snapshot.name(),
                                snapshot.url(),
                                snapshot.enabled(),
                                snapshot.createdAt(),
                                snapshot.updatedAt());
        }
}
