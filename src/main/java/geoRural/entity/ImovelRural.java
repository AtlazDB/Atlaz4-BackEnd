package geoRural.entity;

import jakarta.persistence.*;
import org.locationtech.jts.geom.Geometry;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "IMOVEL_RURAL")
public class ImovelRural {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "COD_IMOVEL", nullable = false, unique = true)
    private String codImovel;

    @Column(name = "MUNICIPIO")
    private String municipio;

    @Column(name = "ESTADO")
    private String estado;

    @Column(name = "AREA_HA")
    private BigDecimal areaHa;

    @Column(name = "GEOMETRIA", columnDefinition = "SDO_GEOMETRY", nullable = false)
    private Geometry geometria;

    @Column(name = "DATASET_VERSAO_ID", nullable = false)
    private Long datasetVersaoId;

    @Column(name = "CRIADO_EM", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    // Getters e Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCodImovel() { return codImovel; }
    public void setCodImovel(String codImovel) { this.codImovel = codImovel; }

    public String getMunicipio() { return municipio; }
    public void setMunicipio(String municipio) { this.municipio = municipio; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public BigDecimal getAreaHa() { return areaHa; }
    public void setAreaHa(BigDecimal areaHa) { this.areaHa = areaHa; }

    public Geometry getGeometria() { return geometria; }
    public void setGeometria(Geometry geometria) { this.geometria = geometria; }

    public Long getDatasetVersaoId() { return datasetVersaoId; }
    public void setDatasetVersaoId(Long datasetVersaoId) { this.datasetVersaoId = datasetVersaoId; }

    public OffsetDateTime getCriadoEm() { return criadoEm; }
}