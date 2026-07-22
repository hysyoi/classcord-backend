package com.hys.classcord.ai.client;

import com.hys.classcord.common.dto.FailMaterialRequest;
import com.hys.classcord.common.dto.InternalMaterialDto;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "classcord-main-service", contextId = "materialClient", path = "/api/internal/materials")
public interface MaterialClient {

    @GetMapping("/{id}")
    InternalMaterialDto getMaterial(@PathVariable UUID id);

    @PutMapping("/{id}/status/processing")
    void markAsProcessing(@PathVariable UUID id);

    @PutMapping("/{id}/status/enabled")
    void markAsEnabled(@PathVariable UUID id);

    @PutMapping("/{id}/status/failed")
    void markAsFailed(
            @PathVariable("id") UUID id,
            @RequestBody FailMaterialRequest request);
}
