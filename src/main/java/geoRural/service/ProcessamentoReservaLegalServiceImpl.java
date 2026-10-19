package geoRural.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import geoRural.dto.ProcessamentoResponse;
import geoRural.exception.ArquivoNaoEncontradoException;
import geoRural.exception.ProcessamentoEmAndamentoException;
import geoRural.exception.RequisicaoInvalidaException;
import geoRural.service.GravadorImoveis.ArquivoParaProcessar;
import geoRural.service.GravadorImoveis.VersaoCriada;
import geoRural.service.ValidadorImovel.Resultado;

/**
 * Reserva Legal do CAR: mesmo caminho do processamento de imóveis (zona bruta → validação →
 * quarentena → gravação em lote), com o shapefile da camada RESERVA_LEGAL do SICAR.
 */
@Service
public class ProcessamentoReservaLegalServiceImpl implements ProcessamentoReservaLegalService {

    public static final String SIGLA = "CAR-RL";

    private static final Logger log = LoggerFactory.getLogger(ProcessamentoReservaLegalServiceImpl.class);

    static final int TAMANHO_LOTE = 1000;

    private final GravadorImoveis gravador;
    private final GravadorReservaLegal gravadorRl;
    private final LeitorShapefile leitor;
    private final ObjectStorageService objectStorage;
    private final TransactionTemplate transacao;

    public ProcessamentoReservaLegalServiceImpl(GravadorImoveis gravador, GravadorReservaLegal gravadorRl,
                                                LeitorShapefile leitor, ObjectStorageService objectStorage,
                                                TransactionTemplate transacao) {
        this.gravador = gravador;
        this.gravadorRl = gravadorRl;
        this.leitor = leitor;
        this.objectStorage = objectStorage;
        this.transacao = transacao;
    }

    @Override
    public ProcessamentoResponse processar(Long arquivoId) {
        ArquivoParaProcessar arquivo = gravador.buscarArquivo(arquivoId)
                .orElseThrow(() -> new ArquivoNaoEncontradoException(arquivoId));

        if (!SIGLA.equalsIgnoreCase(arquivo.siglaFonte())
                || !arquivo.nomeOriginal().toLowerCase().endsWith(".zip")) {
            throw new RequisicaoInvalidaException(
                    "Só arquivos .zip da fonte CAR-RL (shapefile de Reserva Legal) podem ser processados aqui.");
        }
        if (!gravador.marcarProcessando(arquivoId)) {
            throw new ProcessamentoEmAndamentoException(arquivoId);
        }

        Long execucaoId = null;
        Path zip = null;
        try {
            execucaoId = gravador.iniciarExecucao(arquivoId);
            zip = objectStorage.baixar(arquivo.chaveObjeto());

            long execucao = execucaoId;
            Path zipBaixado = zip;
            ProcessamentoResponse resposta = transacao.execute(status -> gravar(arquivoId, execucao, zipBaixado));
            log.info("Reserva Legal do arquivo {} processada: {}", arquivoId, resposta);
            return resposta;
        } catch (Exception e) {
            log.error("Falha ao processar a Reserva Legal do arquivo {}", arquivoId, e);
            String mensagem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            gravador.falhar(arquivoId, execucaoId, mensagem);
            return ProcessamentoResponse.rejeitado(arquivoId, execucaoId, mensagem);
        } finally {
            apagar(zip);
        }
    }

    private ProcessamentoResponse gravar(long arquivoId, long execucaoId, Path zip) {
        VersaoCriada versao = gravador.criarVersao(execucaoId, GravadorReservaLegal.CONJUNTO);
        ValidadorReservaLegal validador = new ValidadorReservaLegal();
        Contagem contagem = new Contagem(versao.id(), execucaoId);

        try (var extraidos = leitor.extrair(zip)) {
            for (Path shp : extraidos.shapefiles()) {
                log.info("Lendo {}", shp.getFileName());
                leitor.ler(shp, rl -> contagem.adicionar(validador.validar(rl)));
            }
        }
        contagem.descarregar();
        int substituidos = gravadorRl.substituirAnteriores(versao.id());
        log.info("{} polígonos de RL de versões anteriores substituídos", substituidos);

        gravador.concluir(arquivoId, execucaoId, versao.id(), contagem.lidos, contagem.validos, contagem.invalidos);
        return ProcessamentoResponse.processado(arquivoId, execucaoId, versao.versao(),
                contagem.lidos, contagem.validos, contagem.invalidos);
    }

    private class Contagem {
        final long versaoId;
        final long execucaoId;
        final List<Resultado> aprovados = new ArrayList<>(TAMANHO_LOTE);
        final List<Resultado> reprovados = new ArrayList<>(TAMANHO_LOTE);
        int lidos;
        int validos;
        int invalidos;

        Contagem(long versaoId, long execucaoId) {
            this.versaoId = versaoId;
            this.execucaoId = execucaoId;
        }

        void adicionar(Resultado resultado) {
            lidos++;
            if (resultado.aprovado()) {
                validos++;
                aprovados.add(resultado);
            } else {
                invalidos++;
                reprovados.add(resultado);
            }
            if (aprovados.size() >= TAMANHO_LOTE || reprovados.size() >= TAMANHO_LOTE) {
                descarregar();
            }
        }

        void descarregar() {
            gravadorRl.gravar(versaoId, aprovados);
            gravador.gravarQuarentena(execucaoId, reprovados);
            aprovados.clear();
            reprovados.clear();
        }
    }

    private static void apagar(Path arquivo) {
        if (arquivo == null) {
            return;
        }
        try {
            Files.deleteIfExists(arquivo);
        } catch (Exception e) {
            log.warn("Não foi possível apagar o temporário {}", arquivo, e);
        }
    }
}
