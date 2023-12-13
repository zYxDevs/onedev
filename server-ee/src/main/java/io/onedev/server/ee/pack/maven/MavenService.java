package io.onedev.server.ee.pack.maven;

import io.onedev.commons.utils.LockUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.server.SubscriptionManager;
import io.onedev.server.entitymanager.*;
import io.onedev.server.event.ListenerRegistry;
import io.onedev.server.event.project.pack.PackPublished;
import io.onedev.server.exception.HttpResponse;
import io.onedev.server.exception.HttpResponseAwareException;
import io.onedev.server.model.Build;
import io.onedev.server.model.Pack;
import io.onedev.server.model.PackBlob;
import io.onedev.server.model.Project;
import io.onedev.server.pack.PackService;
import io.onedev.server.persistence.SessionManager;
import io.onedev.server.persistence.TransactionManager;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.util.Digest;
import io.onedev.server.util.Pair;
import org.apache.shiro.authz.UnauthorizedException;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.MediaType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;

import static io.onedev.server.ee.pack.maven.MavenPackSupport.TYPE;
import static io.onedev.server.util.IOUtils.BUFFER_SIZE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;
import static javax.servlet.http.HttpServletResponse.*;
import static javax.ws.rs.core.HttpHeaders.LAST_MODIFIED;
import static org.apache.commons.io.IOUtils.copyLarge;

@Singleton
public class MavenService implements PackService {

	private static final int MAX_CHECKSUM_SIZE = 1000;
	
	static final String FILE_METADATA = "maven-metadata.xml";

	private static final DateTimeFormatter LAST_UPDATED_FORMATTER = 
			DateTimeFormat.forPattern("yyyyMMddHHmmss");

	private static final String EXT_SHA1 = ".sha1";

	private static final String EXT_MD5 = ".md5";

	private static final String EXT_SHA256 = ".sha256";

	private static final String EXT_SHA512 = ".sha512";
	
	private static final String EXT_XML = ".xml";
	
	private static final String EXT_JAR = ".jar";

	private static final String VERSION_SUFFIX_SNAPSHOT = "-SNAPSHOT";
	
	private static final String CONTENT_TYPE_JAR = "application/java-archive";

	private final SessionManager sessionManager;
	
	private final TransactionManager transactionManager;
	
	private final PackBlobManager packBlobManager;
	
	private final PackManager packManager;
	
	private final PackBlobReferenceManager packBlobReferenceManager;
	
	private final ProjectManager projectManager;
	
	private final ListenerRegistry listenerRegistry;
	
	private final BuildManager buildManager;
	
	private final SubscriptionManager subscriptionManager;
	
	@Inject
	public MavenService(SessionManager sessionManager, TransactionManager transactionManager, 
						PackBlobManager packBlobManager, PackManager packManager, 
						PackBlobReferenceManager packBlobReferenceManager, 
						ProjectManager projectManager, ListenerRegistry listenerRegistry, 
						BuildManager buildManager, SubscriptionManager subscriptionManager) {
		this.sessionManager = sessionManager;
		this.transactionManager = transactionManager;
		this.packBlobManager = packBlobManager;
		this.packManager = packManager;
		this.packBlobReferenceManager = packBlobReferenceManager;
		this.projectManager = projectManager;
		this.listenerRegistry = listenerRegistry;
		this.buildManager = buildManager;
		this.subscriptionManager = subscriptionManager;
	}
	
	@Override
	public String getServiceId() {
		return "maven";
	}

	@Override
	public void service(HttpServletRequest request, HttpServletResponse response, Long projectId, 
						Long buildId, List<String> pathSegments) {
		if (!subscriptionManager.isSubscriptionActive()) {
			throw new HttpResponseAwareException(new HttpResponse(
					SC_NOT_ACCEPTABLE, 
					"This feature requires an active subscription. Visit https://onedev.io/pricing to get a 30 days free trial"));
		}
		
		var method = request.getMethod();
		
		var isHead = method.equals("HEAD");
		var isGet = method.equals("GET");
		
		if (pathSegments.isEmpty())
			throw new HttpResponseAwareException(new HttpResponse(SC_BAD_REQUEST, "No file name"));
		var fileName = pathSegments.get(pathSegments.size() - 1);
		pathSegments = pathSegments.subList(0, pathSegments.size() - 1);
		if (pathSegments.isEmpty())
			throw new HttpResponseAwareException(new HttpResponse(SC_BAD_REQUEST, "No GAV info"));
		var prevSegment = pathSegments.get(pathSegments.size() - 1);
		pathSegments = pathSegments.subList(0, pathSegments.size() - 1);
		if (fileName.startsWith(FILE_METADATA)) {
			if (prevSegment.endsWith(VERSION_SUFFIX_SNAPSHOT)) {
				var pair = getGroupIdAndArtifactId(pathSegments);
				if (isGet || isHead) 
					serveBlob(response, isGet, projectId, pair.getLeft(), pair.getRight(), prevSegment, fileName);
				else 
					uploadBlob(request, response, projectId, buildId, pair.getLeft(), pair.getRight(), prevSegment, fileName);
			} else if (pathSegments.isEmpty()) {
				if (isGet || isHead)
					serveBlob(response, isGet, projectId, prevSegment, null, null, fileName);
				else
					uploadBlob(request, response, projectId, buildId, prevSegment, null, null, fileName);					
			} else {
				var groupId = getGroupId(pathSegments);
				var versionInfos = sessionManager.call(() -> {
					var project = getProject(projectId, false);
					var packs = packManager.queryByGAWithV(project, TYPE, groupId, prevSegment);
					if (!packs.isEmpty() && fileName.equals(FILE_METADATA) && !isHead && !isGet) {
						var latestPack = packs.get(packs.size()-1);
						listenerRegistry.post(new PackPublished(latestPack));
					}
					return packs.stream().map(it -> new Pair<>(it.getVersion(), it.getPublishDate())).collect(toList());
				});
				if (!versionInfos.isEmpty()) {
					if (isGet) {
						var latestVersionInfo = versionInfos.get(versionInfos.size()-1);
						Document metadata = DocumentHelper.createDocument();
						var metadataElement = metadata.addElement("metadata");
						metadataElement.addElement("groupId").setText(groupId);
						metadataElement.addElement("artifactId").setText(prevSegment);
						var versioningElement = metadataElement.addElement("versioning");
						versioningElement.addElement("latest").setText(latestVersionInfo.getLeft());
						String release = null;
						for (var i = versionInfos.size()-1; i>=0; i--) {
							var versionInfo = versionInfos.get(i);
							if (!versionInfo.getLeft().endsWith(VERSION_SUFFIX_SNAPSHOT)) {
								release = versionInfo.getLeft();
								break;
							}
						}
						if (release != null)
							versioningElement.addElement("release").setText(release);
						versioningElement.addElement("lastUpdated").setText(
								LAST_UPDATED_FORMATTER.print(new DateTime(latestVersionInfo.getRight().getTime())));
						var versionsElement = versioningElement.addElement("versions");
						for (var version: versionInfos) 
							versionsElement.addElement("version").setText(version.getLeft());
						
						var bytes = metadata.asXML().getBytes(UTF_8);
						if (getBlobName(fileName).equals(fileName)) {
							response.setContentType(MediaType.APPLICATION_XML);
							response.setContentLength(bytes.length);
							try {
								response.getOutputStream().write(bytes);
							} catch (IOException e) {
								throw new RuntimeException(e);
							}
						} else {
							response.setContentType(MediaType.TEXT_PLAIN);
							String checksum;
							if (fileName.endsWith(EXT_SHA256))
								checksum = Digest.sha256Of(bytes).getHash();
							else if (fileName.endsWith(EXT_MD5))
								checksum = Digest.md5Of(bytes).getHash();
							else if (fileName.endsWith(EXT_SHA1))
								checksum = Digest.sha1Of(bytes).getHash();
							else
								checksum = Digest.sha512Of(bytes).getHash();
							try {
								response.getOutputStream().write(checksum.getBytes(UTF_8));
							} catch (IOException e) {
								throw new RuntimeException(e);
							}
						}
						response.setStatus(SC_OK);
					} else if (isHead) {
						var latestVersion = versionInfos.get(versionInfos.size()-1);							
						response.setStatus(SC_OK);
						response.setDateHeader(LAST_MODIFIED, latestVersion.getRight().getTime());
					} else {
						// Ignore as we will generate metadata from existing versions
						response.setStatus(SC_OK);
					}
				} else if (isGet || isHead) {
					serveBlob(response, isGet, projectId, groupId + "." + prevSegment, 
							null, null, fileName);
				} else {
					uploadBlob(request, response, projectId, buildId, groupId + "." + prevSegment,
							null, null, fileName);
				}
			}
		} else {
			var pair = getGroupIdAndArtifactId(pathSegments);
			if (isGet || isHead) 
				serveBlob(response, isGet, projectId, pair.getLeft(), pair.getRight(), prevSegment, fileName);
			else 
				uploadBlob(request, response, projectId, buildId, pair.getLeft(), pair.getRight(), prevSegment, fileName);
		}
	}
	
	private String getBlobName(String fileName) {
		if (fileName.endsWith(EXT_MD5) || fileName.endsWith(EXT_SHA1) 
				|| fileName.endsWith(EXT_SHA256) || fileName.endsWith(EXT_SHA512)) {
			return StringUtils.substringBeforeLast(fileName, ".");
		} else {
			return fileName;
		}
	}
	
	private String getNonSha256Hash(PackBlob packBlob, String fileName) {
		if (fileName.endsWith(EXT_SHA512))
			return packBlobManager.getSha512Hash(packBlob);
		else if (fileName.endsWith(EXT_MD5))
			return packBlobManager.getMd5Hash(packBlob);
		else 
			return packBlobManager.getSha1Hash(packBlob);
	}

	private void serveBlob(HttpServletResponse response, boolean isGet, Long projectId,
						   String groupId, @Nullable String artifactId,
						   @Nullable String version, String fileName) {
		var packInfo = sessionManager.call(() -> {
			var project = getProject(projectId, false);
			var pack = findPack(project, groupId, artifactId, version);
			if (pack != null)
				return new Pair<>((MavenData)pack.getData(), pack.getPublishDate());
			else 
				return null;
		});
		if (packInfo != null) {
			if (isGet) {
				var blobName = getBlobName(fileName);
				MavenData data = packInfo.getLeft();
				var sha256BlobHash = data.getSha256BlobHashes().get(blobName);
				if (sha256BlobHash != null) {
					try {
						if (!blobName.equals(fileName)) { // serve checksum
							String blobHash;
							if (fileName.endsWith(EXT_SHA256)) {
								blobHash = sha256BlobHash;
							} else {
								blobHash = sessionManager.call(() -> {
									var packBlob = packBlobManager.findBySha256Hash(sha256BlobHash);
									if (packBlob != null && SecurityUtils.canReadPackBlob(packBlob)) {
										if (packBlobManager.checkPackBlobFile(
												packBlob.getProject().getId(), sha256BlobHash, packBlob.getSize())) {
											return getNonSha256Hash(packBlob, fileName);
										} else {
											packBlobManager.delete(packBlob);
											throw new HttpResponseAwareException(new HttpResponse(
													SC_NOT_FOUND, "Invalid file"));
										}
									} else {
										throw new HttpResponseAwareException(new HttpResponse(
												SC_NOT_FOUND, "Missing file"));
									}
								});
							}
							response.setStatus(SC_OK);
							response.setContentType(MediaType.TEXT_PLAIN);
							response.getOutputStream().write(blobHash.getBytes(UTF_8));
						} else {
							var packBlobInfo = sessionManager.call(() -> {
								var packBlob = packBlobManager.findBySha256Hash(sha256BlobHash);
								if (packBlob != null && SecurityUtils.canReadPackBlob(packBlob)) {
									if (packBlobManager.checkPackBlobFile(
											packBlob.getProject().getId(), sha256BlobHash, packBlob.getSize())) {
										return new Pair<>(packBlob.getProject().getId(), packBlob.getSize());
									} else {
										packBlobManager.delete(packBlob);
										throw new HttpResponseAwareException(new HttpResponse(
												SC_NOT_FOUND, "Invalid file"));
									}
								} else {
									throw new HttpResponseAwareException(new HttpResponse(
											SC_NOT_FOUND, "Missing file"));
								}
							});
							response.setContentLengthLong(packBlobInfo.getRight());
							if (fileName.endsWith(EXT_XML))
								response.setContentType(MediaType.APPLICATION_XML);
							else if (fileName.endsWith(EXT_JAR))
								response.setContentType(CONTENT_TYPE_JAR);
							packBlobManager.downloadBlob(packBlobInfo.getLeft(), sha256BlobHash, 
									response.getOutputStream());
							response.setStatus(SC_OK);
						}
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				} else {
					throw new HttpResponseAwareException(new HttpResponse(SC_NOT_FOUND, "Unknown file"));
				}
			} else {
				response.setStatus(SC_OK);
				response.setDateHeader(LAST_MODIFIED, packInfo.getRight().getTime());
			}
		} else {
			throw new HttpResponseAwareException(new HttpResponse(SC_NOT_FOUND, "Unknown GAV"));
		}
	}
	
	private void uploadBlob(HttpServletRequest request, HttpServletResponse response,
							Long projectId, Long buildId, String groupId, @Nullable String artifactId, 
							@Nullable String version, String fileName) {
		try (var is = request.getInputStream()) {
			var lockName = "update-pack:" + projectId + ":" + TYPE + ":" + groupId;
			if (artifactId != null && version != null)
				lockName += ":" + artifactId + ":" + version;
			var blobName = getBlobName(fileName);
			if (!blobName.equals(fileName)) { // checksum verification
				var baos = new ByteArrayOutputStream();
				if (copyLarge(is, baos, 0, MAX_CHECKSUM_SIZE, new byte[BUFFER_SIZE]) >= MAX_CHECKSUM_SIZE) {
					throw new HttpResponseAwareException(new HttpResponse(SC_REQUEST_ENTITY_TOO_LARGE,
							"Checksum is too large"));
				}
				var checksum = new String(baos.toByteArray(), UTF_8);
				LockUtils.run(lockName, () -> transactionManager.run(() -> {
					var project = getProject(projectId, true);
					Pack pack = findPack(project, groupId, artifactId, version);
					if (pack != null) {
						MavenData data = (MavenData) pack.getData();
						var sha256BlobHash = data.getSha256BlobHashes().get(blobName);
						if (sha256BlobHash != null) {
							var packBlob = packBlobManager.findBySha256Hash(sha256BlobHash);
							if (packBlob != null && SecurityUtils.canReadPackBlob(packBlob)) {
								if (packBlobManager.checkPackBlobFile(
										packBlob.getProject().getId(), sha256BlobHash, packBlob.getSize())) {
									String blobHash;
									if (fileName.endsWith(EXT_SHA256))
										blobHash = sha256BlobHash;
									else
										blobHash = getNonSha256Hash(packBlob, fileName);
									if (blobHash.equals(checksum)) {
										packBlobReferenceManager.createIfNotExist(pack, packBlob);
										response.setStatus(SC_OK);
									} else {
										throw new HttpResponseAwareException(new HttpResponse(
												SC_BAD_REQUEST, "Checksum verification failed"));
									}
								} else {
									packBlobManager.delete(packBlob);
									throw new HttpResponseAwareException(new HttpResponse(SC_BAD_REQUEST,
											"Invalid file to verify checksum"));
								}
							} else {
								throw new HttpResponseAwareException(new HttpResponse(SC_BAD_REQUEST,
										"Missing file to verify checksum"));
							}
						} else {
							throw new HttpResponseAwareException(new HttpResponse(SC_BAD_REQUEST,
									"Unknown file to verify checksum"));
						}
					} else {
						throw new HttpResponseAwareException(new HttpResponse(SC_BAD_REQUEST,
								"Unknown GAV to verify checksum"));
					}
				}));			
			} else {
				var packBlobId = packBlobManager.uploadBlob(projectId, is);
				var sha256BlobHash = sessionManager.call(() -> packBlobManager.load(packBlobId).getSha256Hash());
				LockUtils.run(lockName, () -> transactionManager.run(() -> {
					var project = getProject(projectId, true);
					Pack pack = findPack(project, groupId, artifactId, version);
					if (pack == null) {
						pack = new Pack();
						pack.setProject(project);
						pack.setType(TYPE);
						pack.setGroupId(groupId);
						pack.setArtifactId(artifactId);
						pack.setVersion(version);
						pack.setData(new MavenData());
					}
					
					Build build = null;
					if (buildId != null)
						build = buildManager.load(buildId);
					pack.setBuild(build);
					pack.setUser(SecurityUtils.getUser());
					pack.setPublishDate(new Date());
					MavenData data = (MavenData) pack.getData();
					var prevSha256BlobHash = data.getSha256BlobHashes().put(blobName, sha256BlobHash);
					packManager.createOrUpdate(pack);
					if (prevSha256BlobHash != null) {
						for (var blobReference: pack.getBlobReferences()) {
							if (blobReference.getPackBlob().getSha256Hash().equals(prevSha256BlobHash)) {
								packBlobReferenceManager.delete(blobReference);
								break;
							}
						}
					}
					response.setStatus(SC_CREATED);
				}));
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	private Project getProject(Long projectId, boolean needsToWrite) {
		var project = projectManager.load(projectId);
		if (!project.isPackManagement())
			throw new HttpResponseAwareException(new HttpResponse(SC_NOT_ACCEPTABLE, "Package management not enabled for project '" + project.getPath() + "'"));
		else if (needsToWrite && !SecurityUtils.canWritePack(project))
			throw new UnauthorizedException("No package write permission for project: " + project.getPath());
		else if (!needsToWrite && !SecurityUtils.canReadPack(project))
			throw new UnauthorizedException("No package read permission for project: " + project.getPath());
		return project;
	}
	
	private Pack findPack(Project project, String groupId, @Nullable String artifactId,
						  @Nullable String version) {
		if (artifactId != null && version != null)
			return packManager.findByGAV(project, TYPE, groupId, artifactId, version);
		else
			return packManager.findByGWithoutAV(project, TYPE, groupId);
	}

	private Pair<String, String> getGroupIdAndArtifactId(List<String> pathSegments) {
		if (pathSegments.isEmpty())
			throw new HttpResponseAwareException(new HttpResponse(SC_BAD_REQUEST, "No artifact id"));
		var artifactId = pathSegments.get(pathSegments.size()-1);
		pathSegments = pathSegments.subList(0, pathSegments.size()-1);
		return new Pair<>(getGroupId(pathSegments), artifactId);
	}

	private String getGroupId(List<String> pathSegments) {
		if (pathSegments.isEmpty())
			throw new HttpResponseAwareException(new HttpResponse(SC_BAD_REQUEST, "No group id"));
		return StringUtils.join(pathSegments, ".");
	}
	
}