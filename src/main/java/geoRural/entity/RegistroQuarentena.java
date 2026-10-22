package geoRural.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "REGISTRO_QUARENTENA")
public class RegistroQuarentena {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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