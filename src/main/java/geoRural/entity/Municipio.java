package geoRural.entity;

import jakarta.persistence.*;
import org.locationtech.jts.geom.Geometry;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "MUNICIPIO")
public class Municipio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "COD_IBGE", nullable = false, unique = true)
    private String codIbge;

    @Column(name = "NOME", nullable = false)
    private String nome;

    @Column(name = "ESTADO", nullable = false)
    private String estado;

    @Column(name = "REGIAO")
    private String regiao;

    @Column(name = "AREA_KM2")
    private BigDecimal areaKm2;

    @Column(name = "GEOMETRIA", columnDefinition = "SDO_GEOMETRY", nullable = false)
    private Geometry geometria;

    @Column(name = "DATASET_VERSAO_ID", nullable = false)
    private Long datasetVersaoId;

    @Column(name = "CRIADO_EM", insertable = false, updatable = false)
    private OffsetDateTime criadoEm;

    // Getters e Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCodIbge() { return codIbge; }
    public void setCodIbge(String codIbge) { this.codIbge = codIbge; }

    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public String getRegiao() { return regiao; }
    public void setRegiao(String regiao) { this.regiao = regiao; }

    public BigDecimal getAreaKm2() { return areaKm2; }
    public void setAreaKm2(BigDecimal areaKm2) { this.areaKm2 = areaKm2; }

    public Geometry getGeometria() { return geometria; }
    public void setGeometria(Geometry geometria) { this.geometria = geometria; }

    public Long getDatasetVersaoId() { return datasetVersaoId; }
    public void setDatasetVersaoId(Long datasetVersaoId) { this.datasetVersaoId = datasetVersaoId; }

    public OffsetDateTime getCriadoEm() { return criadoEm; }
}
