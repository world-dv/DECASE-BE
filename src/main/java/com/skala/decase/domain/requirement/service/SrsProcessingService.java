package com.skala.decase.domain.requirement.service;

import com.skala.decase.domain.document.domain.Document;
import com.skala.decase.domain.document.repository.DocumentRepository;
import com.skala.decase.domain.document.service.DocumentService;
import com.skala.decase.domain.job.domain.JobName;
import com.skala.decase.domain.member.domain.Member;
import com.skala.decase.domain.member.service.MemberService;
import com.skala.decase.domain.project.domain.Project;
import com.skala.decase.domain.project.service.AIMailService;
import com.skala.decase.domain.project.service.ProjectService;
import com.skala.decase.domain.requirement.controller.dto.request.CreateRfpRequest;
import com.skala.decase.domain.requirement.domain.Requirement;
import com.skala.decase.domain.requirement.exception.RequirementException;
import com.skala.decase.domain.requirement.mapper.RequirementServiceMapper;
import com.skala.decase.domain.requirement.repository.RequirementRepository;
import com.skala.decase.domain.source.service.SourceRepository;
import jakarta.persistence.EntityManager;
import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.envers.internal.entities.mapper.id.AbstractIdMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SrsProcessingService {

    private final SrsProcessor srsProcessor;

    private final ProjectService projectService;
    private final MemberService memberService;
    private final DocumentService documentService;
    private final RequirementServiceMapper requirementServiceMapper;
    private final RequirementRepository requirementRepository;
    private final SourceRepository sourceRepository;
    private final DocumentRepository documentRepository;

    private final EntityManager entityManager;
    private final AIMailService aiMailService;

    @Value("${asis.callback-url}")
    private String asisCallbackUrl;
    @Value("${srs.callback-url}")
    private String srsCallbackUrl;
    @Value("${file.upload.asis-save-path}")
    private String BASE_ASIS_SAVE_PATH;


    /**
     * 요구사항 정의서 최초 생성
     *
     * @param projectId
     * @param memberId
     * @param file      RFP 문서
     */
    @Transactional
    public String createRequirementsSpecification(Long projectId, Long memberId, MultipartFile file) {
        Project project = projectService.findByProjectId(projectId);
        Member member = memberService.findByMemberId(memberId);
        Document document = documentService.uploadRFP(project, member, file);  //RFP 파일 db에 저장
        entityManager.flush();

        processInParallel(file, projectId, memberId, document.getDocId());
        return document.getDocId();
    }

    /**
     * 병렬처리 요구사항 정의서 생성, as-is 분석 에이전트 호출
     */
    public void processInParallel(MultipartFile file, Long projectId, Long memberId, String rfpDocId) {
        log.info("병렬 처리 시작 - 프로젝트: {}", projectId);
        String formattedCallbackUrl = asisCallbackUrl.replace("{projectId}", projectId.toString());
        String formattedSrsCallbackUrl = srsCallbackUrl.replace("{projectId}", projectId.toString());

        CompletableFuture<Map> asisFuture = srsProcessor.processASIS(projectId, memberId, rfpDocId, file,
                formattedCallbackUrl);
        CompletableFuture<Map> requirementsFuture = srsProcessor.processRequirements(file, projectId, memberId,
                rfpDocId, formattedSrsCallbackUrl);

        try {
            CompletableFuture.allOf(requirementsFuture, asisFuture).get();
        } catch (Exception e) {
            log.error("병렬 처리 실패 - 프로젝트: {}", projectId, e);
            throw new RequirementException("요구사항 정의서 생성 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // 즉시 반환 (블로킹하지 않음)
        log.info("병렬 처리 요청 완료 - 프로젝트: {} (백그라운드에서 계속 진행)", projectId);
    }

    /**
     * AS-IS 분석 결과 저장
     *
     * @param projectId
     * @param memberId
     * @param file      AS-IS 분석 결과 PDF 파일
     */
    @Transactional
    public void saveAsIsAnalysis(Long projectId, Long memberId, MultipartFile file, String status) {
        Project project = projectService.findByProjectId(projectId);
        Member member = memberService.findByMemberId(memberId);

        if (status.equals("COMPLETED")) {
            Document asisPdf = documentService.uploadASIS(project, member, file);
            saveAsisHtml(asisPdf);
            log.info("AS-IS 분석 결과 파일 저장 완료 - 프로젝트 ID: {}", projectId);
            aiMailService.sendMail(JobName.ASIS, member, status, project,1);  // 메일 전송
        } else {
            throw new RequirementException("AS-IS 분석 실패. 상태: " + status + " - 프로젝트 ID: " + projectId,
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * AS-IS PDF 파일을 HTML로 복사 저장
     */
    @Transactional
    public void saveAsisHtml(Document asisPdf) {
        try {
            // 기존 PDF 파일 경로와 이름
            String pdfPath = asisPdf.getPath() + File.separator + asisPdf.getName();

            // .pdf를 .html로 변경한 이름과 경로
            String htmlName = asisPdf.getName().replaceFirst("\\.pdf$", ".html");
            String htmlFilePath = BASE_ASIS_SAVE_PATH + File.separator + htmlName;

            // 새로운 Document 객체 생성 (name, path만 .html로 변경, 나머지는 asisPdf와 동일)
            Document htmlDoc = new Document(
                    "V" + asisPdf.getDocId(), // view 용 asis 파일
                    htmlName,
                    htmlFilePath,
                    asisPdf.isMemberUpload(),
                    asisPdf.getProject(),
                    asisPdf.getCreatedBy()
            );

            documentRepository.save(htmlDoc);
            log.info("AS-IS PDF 파일을 HTML로 복사 및 Document 저장 완료: {} -> {}", pdfPath, htmlFilePath);
        } catch (Exception e) {
            log.error("AS-IS PDF를 HTML로 저장 중 오류 발생", e);
            throw new RequirementException("AS-IS PDF를 HTML로 저장 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 요구사항 정의서 생성 결과 저장
     *
     * @param projectId
     * @param memberId
     */
    @Transactional
    public void saveSRSAnalysis(Long projectId, Long memberId, String documentId, String status,
                                List<CreateRfpRequest> srs) {
        Project project = projectService.findByProjectId(projectId);
        Member member = memberService.findByMemberId(memberId);
        Document document = documentService.findByDocId(documentId);

        if (!status.equals("COMPLETED")) {
            documentRepository.delete(document);  //요구사항 정의서 저장 실패시 사용자 업로드 RFP 삭제
            throw new RequirementException("요구사항 정의서 저장 실패. 상태: " + status + " - 프로젝트 ID: " + projectId,
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        for (CreateRfpRequest req : srs) {
            Requirement requirement = requirementRepository.save(
                    requirementServiceMapper.toREQEntity(req, member, project));
            if (req.sources() != null) {
                req.sources().forEach(sourceReq -> {
                    sourceRepository.save(requirementServiceMapper.toSrcEntity(sourceReq, requirement, document));
                });
            }
        }
        aiMailService.sendMail(JobName.SRS, member, status, project,1);  // 메일 전송
        log.info("요구사항 정의서 및 출처 저장 완료 - 프로젝트 ID: {}", projectId);
    }
}
