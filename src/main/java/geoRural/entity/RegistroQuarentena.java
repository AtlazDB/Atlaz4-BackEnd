package geoRural.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.locationtech.jts.geom.Geometry;

import java.time.OffsetDateTime;

@Entity
@Table(name = "REGISTRO_QUARENTENA")
public class RegistroQuarentena {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Colunas da V1: de onde o registro veio e o que ele trazia.
    @Column(name = "EXECUCAO_ID", nullable = false)
    private Long execucaoId;

    @Column(name = "NUMERO_LINHA")
    private Long numeroLinha;

    @Column(name = "CAMPO", length = 80)
    private String campo;

    @Column(name = "MOTIVO", nullable = false, length = 400)
    private String motivo;

    @Lob
    @Column(name = "PAYLOAD")
    private String payload;

    // Fora do JSON da listagem: a tela pede pelo GET /quarentena/{id}/geometria, já em GeoJSON.
    @JsonIgnore
    @Column(name = "GEOMETRIA", columnDefinition = "SDO_GEOMETRY")
    private Geometry geometria;

    @Column(name = "ENTIDADE", length = 80)
    private String entidade;

    @Column(name = "CHAVE_NATURAL", length = 100)
    private String chaveNatural;

    @Column(name = "TIPO_ERRO", length = 40)
    private String tipoErro;

    @Column(name = "VALOR_RECEBIDO", length = 4000)
    private String valorRecebido;

    @Column(name = "STATUS_ANALISE", nullable = false, length = 30)
    private String statusAnalise = "PENDENTE";

    @Lob
    @Column(name = "PAYLOAD_CORRIGIDO")
    private String payloadCorrigido;

    @Column(name = "JUSTIFICATIVA", length = 1000)
    private String justificativa;

    @Column(name = "ANALISADO_EM")
    private OffsetDateTime analisadoEm;

    @Column(name = "ANALISADO_POR", length = 100)
    private String analisadoPor;

    @Column(name = "DATASET_VERSAO_ID")
    private Long datasetVersaoId;

    // Getters e Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getExecucaoId() { return execucaoId; }
    public void setExecucaoId(Long execucaoId) { this.execucaoId = execucaoId; }

    public Long getNumeroLinha() { return numeroLinha; }
    public void setNumeroLinha(Long numeroLinha) { this.numeroLinha = numeroLinha; }

    public String getCampo() { return campo; }
    public void setCampo(String campo) { this.campo = campo; }

    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Geometry getGeometria() { return geometria; }
    public void setGeometria(Geometry geometria) { this.geometria = geometria; }

    public String getEntidade() { return entidade; }
    public void setEntidade(String entidade) { this.entidade = entidade; }

    public String getChaveNatural() { return chaveNatural; }
    public void setChaveNatural(String chaveNatural) { this.chaveNatural = chaveNatural; }

    public String getTipoErro() { return tipoErro; }
    public void setTipoErro(String tipoErro) { this.tipoErro = tipoErro; }

    public String getValorRecebido() { return valorRecebido; }
    public void setValorRecebido(String valorRecebido) { this.valorRecebido = valorRecebido; }

    public String getStatusAnalise() { return statusAnalise; }
    public void setStatusAnalise(String statusAnalise) { this.statusAnalise = statusAnalise; }

    public String getPayloadCorrigido() { return payloadCorrigido; }
    public void setPayloadCorrigido(String payloadCorrigido) { this.payloadCorrigido = payloadCorrigido; }

    public String getJustificativa() { return justificativa; }
    public void setJustificativa(String justificativa) { this.justificativa = justificativa; }

    public OffsetDateTime getAnalisadoEm() { return analisadoEm; }
    public void setAnalisadoEm(OffsetDateTime analisadoEm) { this.analisadoEm = analisadoEm; }

    public String getAnalisadoPor() { return analisadoPor; }
    public void setAnalisadoPor(String analisadoPor) { this.analisadoPor = analisadoPor; }

    public Long getDatasetVersaoId() { return datasetVersaoId; }
    public void setDatasetVersaoId(Long datasetVersaoId) { this.datasetVersaoId = datasetVersaoId; }
}