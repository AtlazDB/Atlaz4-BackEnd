package geoRural.dto;

import java.math.BigDecimal;
import java.util.Map;

import org.locationtech.jts.geom.Geometry;

/**
 * Um imóvel como veio do shapefile, antes de qualquer validação.
 * {@code atributos} guarda todas as colunas do .dbf, para registrar na quarentena se precisar.
 */
public record ImovelLido(int linha, String codImovel, String municipio,
                         BigDecimal areaHa, String situacao, Geometry geometria,
                         Map<String, Object> atributos) {}
