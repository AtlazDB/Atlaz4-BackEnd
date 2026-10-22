package geoRural.dto;

import jakarta.validation.constraints.NotBlank;

public record CorrecaoRequestDTO(
    @NotBlank(message = "O payload corrigido é obrigatório.")
    String payloadCorrigido,
    String justificativa
) {}
