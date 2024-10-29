/*
 *
 * Copyright 2015 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package com.netflix.imflibrary.writerTools;

import com.netflix.imflibrary.IMFErrorLogger;
import com.netflix.imflibrary.IMFErrorLoggerImpl;
import com.netflix.imflibrary.exceptions.IMFAuthoringException;
import com.netflix.imflibrary.st2067_2.Composition;
import com.netflix.imflibrary.st2067_2.CoreConstraints;
import com.netflix.imflibrary.st2067_2.IMFEssenceComponentVirtualTrack;
import com.netflix.imflibrary.st2067_2.IMFEssenceDescriptorBaseType;
import com.netflix.imflibrary.st2067_2.IMFMarkerResourceType;
import com.netflix.imflibrary.st2067_2.IMFMarkerType;
import com.netflix.imflibrary.st2067_2.IMFMarkerVirtualTrack;
import com.netflix.imflibrary.st2067_2.IMFTrackFileResourceType;
import com.netflix.imflibrary.utils.ErrorLogger;
import com.netflix.imflibrary.utils.UUIDHelper;
import com.netflix.imflibrary.writerTools.utils.IMFUUIDGenerator;
import com.netflix.imflibrary.writerTools.utils.IMFUtils;
import com.netflix.imflibrary.writerTools.utils.ValidationEventHandlerImpl;
import org.smpte_ra.schemas._2067_3._2013.CompositionPlaylistType;
import org.smpte_ra.schemas._2067_3._2013.CompositionTimecodeType;
import org.smpte_ra.schemas._2067_3._2013.ContentVersionType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.annotation.Nonnull;
import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A class that implements the logic to build a SMPTE st2067-2:2013 schema compliant CompositionPlaylist document.
 */
public class CompositionPlaylistBuilder_2013 {

    private final UUID uuid;
    private final org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType annotationText;
    private final org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType issuer;
    private final org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType creator;
    private final XMLGregorianCalendar issueDate;
    private final List<? extends Composition.VirtualTrack> virtualTracks;
    private final List<Long> compositionEditRate;
    private final Long totalRunningTime;
    private final Map<UUID, IMPBuilder.IMFTrackFileInfo> trackFileInfoMap;
    private final Path workingDirectory;
    private final IMFErrorLogger imfErrorLogger;
    private final Map<Node, String> essenceDescriptorIDMap = new HashMap<>();
    private final List<org.smpte_ra.schemas._2067_3._2013.SegmentType> segments = new ArrayList<>();
    private final List<List<org.smpte_ra.schemas._2067_3._2013.SequenceType>> sequenceList = new ArrayList<>();
    private final List<IMFEssenceDescriptorBaseType> imfEssenceDescriptorBaseTypeList;

    public final static String defaultHashAlgorithm = "http://www.w3.org/2000/09/xmldsig#sha1";
    private final static String defaultContentKindScope = "http://www.smpte-ra.org/schemas/2067-3/XXXX#content-kind";
    private final String cplFileName;
    private final String coreConstraintsSchema;
    private final Set<String> applicationIds;
    private final Map<UUID, UUID> trackResourceSourceEncodingMap;

    /**
     * A constructor for CompositionPlaylistBuilder class to build a CompositionPlaylist document compliant with st2067-2:2013 schema
     * @param uuid identifying the CompositionPlaylist document
     * @param annotationText a free form human readable text
     * @param issuer a free form human readable text describing the issuer of the CompositionPlaylist document
     * @param creator a free form human readable text describing the tool used to create the CompositionPlaylist document
     * @param virtualTracks a list of VirtualTracks of the Composition
     * @param compositionEditRate the edit rate of the Composition
     * @param applicationIds ApplicationIds for the composition
     * @param totalRunningTime a long value representing in seconds the total running time of this composition
     * @param trackFileInfoMap a map of the IMFTrackFile's UUID to the track file info
     * @param workingDirectory a folder location where the constructed CPL document can be written to
     * @param imfEssenceDescriptorBaseTypeList List of IMFEssenceDescriptorBaseType
     * @param coreConstraintsSchema schema defining core constraints version
     */
    public CompositionPlaylistBuilder_2013(@Nonnull UUID uuid,
                                           @Nonnull org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType annotationText,
                                           @Nonnull org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType issuer,
                                           @Nonnull org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType creator,
                                           @Nonnull List<? extends Composition.VirtualTrack> virtualTracks,
                                           @Nonnull Composition.EditRate compositionEditRate,
                                           @Nonnull Set<String> applicationIds,
                                           long totalRunningTime,
                                           @Nonnull Map<UUID, IMPBuilder.IMFTrackFileInfo> trackFileInfoMap,
                                           @Nonnull Path workingDirectory,
                                           @Nonnull List<IMFEssenceDescriptorBaseType> imfEssenceDescriptorBaseTypeList,
                                           @Nonnull String coreConstraintsSchema,
                                           Map<UUID, UUID> trackFileIdToEssenceDescriptorIdMap){
        this.uuid = uuid;
        this.annotationText = annotationText;
        this.issuer = issuer;
        this.creator = creator;
        this.issueDate = IMFUtils.createXMLGregorianCalendar();
        this.virtualTracks = Collections.unmodifiableList(virtualTracks);
        this.compositionEditRate = Collections.unmodifiableList(Arrays.asList(compositionEditRate.getNumerator(), compositionEditRate.getDenominator()));
        this.totalRunningTime = totalRunningTime;
        this.trackFileInfoMap = Collections.unmodifiableMap(trackFileInfoMap);
        this.workingDirectory = workingDirectory;
        this.imfErrorLogger = new IMFErrorLoggerImpl();
        this.cplFileName = "CPL-" + this.uuid.toString() + ".xml";
        this.applicationIds = Collections.unmodifiableSet(applicationIds);
        this.imfEssenceDescriptorBaseTypeList = Collections.unmodifiableList(imfEssenceDescriptorBaseTypeList);
        this.coreConstraintsSchema = coreConstraintsSchema;

        Map<UUID, UUID> trackEncodingMap = new HashMap<>(); //Map of TrackFileId -> SourceEncodingElement of each resource of this VirtualTrack
        for(Composition.VirtualTrack virtualTrack : virtualTracks) {
            if (!(virtualTrack instanceof IMFEssenceComponentVirtualTrack)) {
                continue; // Skip non-essence tracks
            }

            IMFEssenceComponentVirtualTrack essenceTrack = (IMFEssenceComponentVirtualTrack) virtualTrack;
            for (IMFTrackFileResourceType trackResource : essenceTrack.getTrackFileResourceList()) {
                UUID sourceEncoding = trackEncodingMap.get(UUIDHelper.fromUUIDAsURNStringToUUID(trackResource.getTrackFileId()));
                if (sourceEncoding == null) {
                    if (trackFileIdToEssenceDescriptorIdMap != null) {
                        trackEncodingMap.put(UUIDHelper.fromUUIDAsURNStringToUUID(trackResource.getTrackFileId()), trackFileIdToEssenceDescriptorIdMap.get(UUIDHelper.fromUUIDAsURNStringToUUID(trackResource.getTrackFileId())) /*UUIDHelper.fromUUIDAsURNStringToUUID(trackResource.getSourceEncoding())*/);
                    }
                    else {
                        trackEncodingMap.put(UUIDHelper.fromUUIDAsURNStringToUUID(trackResource.getTrackFileId()), UUIDHelper.fromUUIDAsURNStringToUUID(trackResource.getSourceEncoding()));
                    }
                    // trackEncodingMap.put(UUIDHelper.fromUUIDAsURNStringToUUID(trackResource.getTrackFileId()), trackFileIdToEssenceDescriptorIdMap.get(trackResource.getTrackFileId()) /*UUIDHelper.fromUUIDAsURNStringToUUID(trackResource.getSourceEncoding())*/);
                }
            }
        }
        this.trackResourceSourceEncodingMap = Collections.unmodifiableMap(trackEncodingMap);
    }

    /**
     * A constructor for CompositionPlaylistBuilder class to build a CompositionPlaylist document compliant with st2067-2:2013 schema
     * @param uuid identifying the CompositionPlaylist document
     * @param annotationText a free form human readable text
     * @param issuer a free form human readable text describing the issuer of the CompositionPlaylist document
     * @param creator a free form human readable text describing the tool used to create the CompositionPlaylist document
     * @param virtualTracks a list of VirtualTracks of the Composition
     * @param compositionEditRate the edit rate of the Composition
     * @param applicationId ApplicationId for the composition
     * @param totalRunningTime a long value representing in seconds the total running time of this composition
     * @param trackFileInfoMap a map of the IMFTrackFile's UUID to the track file info
     * @param workingDirectory a folder location where the constructed CPL document can be written to
     * @param imfEssenceDescriptorBaseTypeList List of IMFEssenceDescriptorBaseType
     */
    @Deprecated
    public CompositionPlaylistBuilder_2013(@Nonnull UUID uuid,
                                           @Nonnull org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType annotationText,
                                           @Nonnull org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType issuer,
                                           @Nonnull org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType creator,
                                           @Nonnull List<? extends Composition.VirtualTrack> virtualTracks,
                                           @Nonnull Composition.EditRate compositionEditRate,
                                           @Nonnull String applicationId,
                                           long totalRunningTime,
                                           @Nonnull Map<UUID, IMPBuilder.IMFTrackFileInfo> trackFileInfoMap,
                                           @Nonnull Path workingDirectory,
                                           @Nonnull List<IMFEssenceDescriptorBaseType> imfEssenceDescriptorBaseTypeList){
        this(uuid, annotationText, issuer, creator, virtualTracks, compositionEditRate, Collections.singleton(applicationId), totalRunningTime, trackFileInfoMap, workingDirectory, imfEssenceDescriptorBaseTypeList, CoreConstraints.NAMESPACE_IMF_2013, null);
    }


    /**
     * A constructor for CompositionPlaylistBuilder class to build a CompositionPlaylist document compliant with st2067-2:2013 schema
     * @param uuid identifying the CompositionPlaylist document
     * @param annotationText a free form human readable text
     * @param issuer a free form human readable text describing the issuer of the CompositionPlaylist document
     * @param creator a free form human readable text describing the tool used to create the CompositionPlaylist document
     * @param virtualTracks a list of VirtualTracks of the Composition
     * @param compositionEditRate the edit rate of the Composition
     * @param applicationId ApplicationId for the composition
     * @param totalRunningTime a long value representing in seconds the total running time of this composition
     * @param trackFileInfoMap a map of the IMFTrackFile's UUID to the track file info
     * @param workingDirectory a folder location where the constructed CPL document can be written to
     */
    @Deprecated
    public CompositionPlaylistBuilder_2013(@Nonnull UUID uuid,
                                           @Nonnull org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType annotationText,
                                           @Nonnull org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType issuer,
                                           @Nonnull org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType creator,
                                           @Nonnull List<? extends Composition.VirtualTrack> virtualTracks,
                                           @Nonnull Composition.EditRate compositionEditRate,
                                           @Nonnull String applicationId,
                                           long totalRunningTime,
                                           @Nonnull Map<UUID, IMPBuilder.IMFTrackFileInfo> trackFileInfoMap,
                                           @Nonnull Path workingDirectory){
        this(uuid, annotationText, issuer, creator, virtualTracks, compositionEditRate, applicationId, totalRunningTime, trackFileInfoMap, workingDirectory, new ArrayList<>());
    }
    

    /**
     * A method to build a CompositionPlaylist document conforming to the st2067-2/3:2013 schema
     * @return a list of errors resulting during the creation of the CPL document
     * @throws IOException - any I/O related error is exposed through an IOException
     * @throws ParserConfigurationException if a DocumentBuilder
     *   cannot be created which satisfies the configuration requested
     */
    public List<ErrorLogger.ErrorObject> build() throws IOException, ParserConfigurationException {
        org.smpte_ra.schemas._2067_3._2013.CompositionPlaylistType cplRoot = IMFCPLObjectFieldsFactory.constructCompositionPlaylistType_2013();
        IMFErrorLogger imfErrorLogger = new IMFErrorLoggerImpl();

        cplRoot.setId(UUIDHelper.fromUUID(this.uuid));
        cplRoot.setAnnotation(this.annotationText);
        cplRoot.setIssueDate(IMFUtils.createXMLGregorianCalendar());
        cplRoot.setIssuer(this.issuer);
        cplRoot.setCreator(this.creator);
        cplRoot.setContentOriginator(null);
        cplRoot.setContentTitle(buildCPLUserTextType_2013("Not Included", "en"));
        cplRoot.setContentKind(null);
        org.smpte_ra.schemas._2067_3._2013.ContentVersionType contentVersionType = buildContentVersionType(IMFUUIDGenerator.getInstance().getUrnUUID(), buildCPLUserTextType_2013("Photon CompositionPlaylistBuilder", "en"));
        List<org.smpte_ra.schemas._2067_3._2013.ContentVersionType> contentVersionTypeList = new ArrayList<>();
        contentVersionTypeList.add(contentVersionType);
        cplRoot.setContentVersionList(buildContentVersionList(contentVersionTypeList));
        cplRoot.setLocaleList(null);
        cplRoot.setCompositionTimecode(null);
        cplRoot.setExtensionProperties(null);
        cplRoot.getEditRate().addAll(this.compositionEditRate);
        cplRoot.setTotalRunningTime(String.format("%02d:%02d:%02d", totalRunningTime / 3600, (totalRunningTime % 3600) / 60, (totalRunningTime % 60)));

        /**
         * Process each VirtualTrack that is a part of this Composition
         */
        List<CompositionPlaylistBuilder_2013.SequenceTypeTuple> sequenceTypeTuples = new ArrayList<>();

        for(Composition.VirtualTrack virtualTrack : virtualTracks) {
            /**
             * Build TrackResourceList
             */
            List<org.smpte_ra.schemas._2067_3._2013.BaseResourceType> trackResourceList = buildTrackResourceList(virtualTrack);
            /**
             * Build the Sequence
             */
            UUID sequenceId = IMFUUIDGenerator.getInstance().generateUUID();
            UUID trackId = IMFUUIDGenerator.getInstance().generateUUID();
            SequenceTypeTuple sequenceTypeTuple = buildSequenceTypeTuple(sequenceId, trackId, buildResourceList(trackResourceList), virtualTrack.getSequenceTypeEnum());
            sequenceTypeTuples.add(sequenceTypeTuple);
        }
        org.smpte_ra.schemas._2067_3._2013.CompositionPlaylistType.EssenceDescriptorList essenceDescriptorListType = buildEssenceDescriptorList(this.imfEssenceDescriptorBaseTypeList);
        cplRoot.setEssenceDescriptorList(essenceDescriptorListType);
        UUID segmentId = IMFUUIDGenerator.getInstance().generateUUID();
        org.smpte_ra.schemas._2067_3._2013.SegmentType segmentType = buildSegment(segmentId, buildCPLUserTextType_2013("Segment-1", "en"));
        populateSequenceListForSegment(sequenceTypeTuples, segmentType);
        cplRoot.setSegmentList(buildSegmentList(new ArrayList<org.smpte_ra.schemas._2067_3._2013.SegmentType>(){{add(segmentType);}}));
        cplRoot.setSigner(null);
        cplRoot.setSignature(null);
        if (!this.applicationIds.isEmpty())
        {
            JAXBElement<List<String>> appIdElement = new org.smpte_ra.schemas._2067_2._2013.ObjectFactory()
                    .createApplicationIdentification(new ArrayList<>(this.applicationIds));

            org.smpte_ra.schemas._2067_3._2013.CompositionPlaylistType.ExtensionProperties extensionProperties = new org.smpte_ra.schemas._2067_3._2013.CompositionPlaylistType.ExtensionProperties();
            extensionProperties.getAny().add(appIdElement);
            cplRoot.setExtensionProperties( extensionProperties);
        }

        Path outputPath = this.workingDirectory.resolve(this.cplFileName);
        List errors = serializeCPLToXML(cplRoot, outputPath);
        imfErrorLogger.addAllErrors(errors);
        return imfErrorLogger.getErrors();
    }

    private List<org.smpte_ra.schemas._2067_3._2013.BaseResourceType> buildTrackResourceList(Composition.VirtualTrack virtualTrack){
        List<org.smpte_ra.schemas._2067_3._2013.BaseResourceType> trackResourceList = new ArrayList<>();

        // Wrap essence track file resources into the JAXB class
        if (virtualTrack instanceof IMFEssenceComponentVirtualTrack) {
            IMFEssenceComponentVirtualTrack essenceTrack = (IMFEssenceComponentVirtualTrack) virtualTrack;
            for (IMFTrackFileResourceType trackFileResource : essenceTrack.getTrackFileResourceList()) {
                trackResourceList.add(buildTrackFileResource(trackFileResource));
            }
        }
        // Wrap marker track resources into the JAXB class
        else if (virtualTrack instanceof IMFMarkerVirtualTrack)
        {
            IMFMarkerVirtualTrack markerTrack = (IMFMarkerVirtualTrack) virtualTrack;
            for (IMFMarkerResourceType markerResource : markerTrack.getMarkerResourceList()) {
                trackResourceList.add(buildMarkerResource(markerResource));
            }
        }
        return Collections.unmodifiableList(trackResourceList);
    }

    private List<ErrorLogger.ErrorObject> serializeCPLToXML(org.smpte_ra.schemas._2067_3._2013.CompositionPlaylistType cplRoot,
                                                 Path outputPath) throws IOException
    {
        IMFErrorLogger imfErrorLogger = new IMFErrorLoggerImpl();
        boolean formatted = true;

        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        InputStream cplSchemaAsAStream = contextClassLoader.getResourceAsStream("org/smpte_ra/schemas/st2067_3_2013/imf-cpl.xsd");
        InputStream dcmlSchemaAsAStream = contextClassLoader.getResourceAsStream("org/smpte_ra/schemas/st0433_2008/dcmlTypes/dcmlTypes.xsd");
        try(InputStream dsigSchemaAsAStream = contextClassLoader.getResourceAsStream
                ("org/w3/_2000_09/xmldsig/xmldsig-core-schema.xsd");
            InputStream coreConstraintsSchemaAsAStream = contextClassLoader.getResourceAsStream("org/smpte_ra/schemas/st2067_2_2013/imf-core-constraints-20130620-pal.xsd");

            SeekableByteChannel byteChannel = Files.newByteChannel(outputPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            OutputStream outputStream = Channels.newOutputStream(byteChannel);)

        {
            try
            {
                SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                StreamSource[] schemaSources = new StreamSource[4];
                schemaSources[0] = new StreamSource(dsigSchemaAsAStream);
                schemaSources[1] = new StreamSource(dcmlSchemaAsAStream);
                schemaSources[2] = new StreamSource(cplSchemaAsAStream);
                schemaSources[3] = new StreamSource(coreConstraintsSchemaAsAStream);
                Schema schema = schemaFactory.newSchema(schemaSources);

                JAXBContext jaxbContext = JAXBContext.newInstance(org.smpte_ra.schemas._2067_3._2013.ObjectFactory.class,  // 2013 CPL
                                                                    org.smpte_ra.schemas._2067_2._2013.ObjectFactory.class);                    // 2013 Core Constraints
                Marshaller marshaller = jaxbContext.createMarshaller();
                ValidationEventHandlerImpl validationEventHandler = new ValidationEventHandlerImpl(true);
                marshaller.setEventHandler(validationEventHandler);
                marshaller.setSchema(schema);
                marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, formatted);

                /*marshaller.marshal(cplType, output);
                workaround for 'Error: unable to marshal type "CompositionPlaylistType" as an element because it is missing an @XmlRootElement annotation'
                as found at https://weblogs.java.net/blog/2006/03/03/why-does-jaxb-put-xmlrootelement-sometimes-not-always
                 */
                marshaller.marshal(new JAXBElement<>(new QName("http://www.smpte-ra.org/schemas/2067-3/2013", "CompositionPlaylist"), CompositionPlaylistType.class, cplRoot), outputStream);
                if (validationEventHandler.hasErrors()) {
                    //TODO : Perhaps a candidate for a Lambda
                    for (ValidationEventHandlerImpl.ValidationErrorObject validationErrorObject : validationEventHandler.getErrors()) {
                        imfErrorLogger.addError(IMFErrorLogger.IMFErrors.ErrorCodes.IMF_CPL_ERROR, validationErrorObject
                                .getValidationEventSeverity(), validationErrorObject.getErrorMessage());
                    }
                }
            }
            catch( SAXException | JAXBException e)
            {
                imfErrorLogger.addError(IMFErrorLogger.IMFErrors.ErrorCodes.IMF_CPL_ERROR, IMFErrorLogger.IMFErrors
                                .ErrorLevels.FATAL,
                        e.getMessage());
            }
        }


        return imfErrorLogger.getErrors();
    }

    /**
     * A method to construct a UserTextType compliant with the 2013 schema for IMF CompositionPlaylist documents
     * @param value the string that is a part of the annotation text
     * @param language the language code of the annotation text
     * @return a UserTextType conforming to the 2013 schema
     */
    public static org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType buildCPLUserTextType_2013(String value, String language){
        org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType userTextType = new org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType();
        userTextType.setValue(value);
        userTextType.setLanguage(language);
        return userTextType;
    }

    /**
     * A method to construct a ContentKindType object conforming to the 2013 schema
     * @param value the string correspding to the Content Kind
     * @param scope a string corresponding to the scope attribute of a Content Kind
     * @return a ContentKind object conforming to the 2013 schema
     */
    public org.smpte_ra.schemas._2067_3._2013.ContentKindType buildContentKindType(@Nonnull String value, String scope)  {

        org.smpte_ra.schemas._2067_3._2013.ContentKindType contentKindType = new org.smpte_ra.schemas._2067_3._2013.ContentKindType();
        if(!scope.matches("^[a-zA-Z0-9._-]+") == true) {
            this.imfErrorLogger.addError(new ErrorLogger.ErrorObject(IMFErrorLogger.IMFErrors.ErrorCodes.IMF_CPL_ERROR, IMFErrorLogger.IMFErrors.ErrorLevels.NON_FATAL, String.format("The ContentKind scope %s does not follow the syntax of a valid URI (a-z, A-Z, 0-9, ., _, -)", scope)));
            contentKindType.setScope(scope);
        }
        else{
            contentKindType.setScope(scope);
        }
        contentKindType.setValue(value);
        return contentKindType;
    }

    /**
     * A method to construct a ContentVersionType object conforming to the 2013 schema
     * @param id urn uuid corresponding to the content version type
     * @param value a UserTextType representing the value attribute of the ContentVersion
     * @return a content version object conforming to the 2013 schema
     */
    public org.smpte_ra.schemas._2067_3._2013.ContentVersionType buildContentVersionType(String id, org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType value) {
        ContentVersionType contentVersionType = new ContentVersionType();
        contentVersionType.setId(id);
        contentVersionType.setLabelText(value);
        return contentVersionType;
    }

    /**
     * A method to construct a ContentVersionList object conforming to the 2013 schema
     * @param contentVersions a list of ContentVersion objects conforming to the 2013 schema
     * @return a content version list object conforming to the 2013 schema
     */
    public org.smpte_ra.schemas._2067_3._2013.CompositionPlaylistType.ContentVersionList buildContentVersionList(List<org.smpte_ra.schemas._2067_3._2013.ContentVersionType> contentVersions){
        org.smpte_ra.schemas._2067_3._2013.CompositionPlaylistType.ContentVersionList contentVersionList = new CompositionPlaylistType.ContentVersionList();
        contentVersionList.getContentVersion().addAll(contentVersions);
        return contentVersionList;
    }

    /**
     * A method to construct an EssenceDescriptorBaseType object conforming to the 2013 schema
     * @param id a UUID identifying the EssenceDescriptor. Note : This value should be the same as the SourceEncoding Element of
     *           the resource in a VirtualTrack of this composition whose EssenceDescriptor it represents in the EssenceDescriptorList. This cannot be enforced
     *           hence the responsibility is with the caller to ensure this else the generated CPL fail validation checks.
     * @param node a regxml representation of an EssenceDescriptor
     * @return a EssenceDescriptorBaseType object conforming to the 2013 schema
     */
    public org.smpte_ra.schemas._2067_3._2013.EssenceDescriptorBaseType buildEssenceDescriptorBaseType(UUID id, Node node){
        org.smpte_ra.schemas._2067_3._2013.EssenceDescriptorBaseType essenceDescriptorBaseType = new org.smpte_ra.schemas._2067_3._2013.EssenceDescriptorBaseType();
        essenceDescriptorBaseType.setId(UUIDHelper.fromUUID(id));
        this.essenceDescriptorIDMap.put(node, UUIDHelper.fromUUID(id));
        essenceDescriptorBaseType.getAny().add(node);
        return essenceDescriptorBaseType;
    }

    /**
     * A method to construct an EssenceDescriptorList conforming to the 2013 schema
     * @param imfEssenceDescriptorBaseTypeList a list of IMFEssenceDescriptorBaseType objects
     * @return EssenceDescriptorList type object
     */
    public org.smpte_ra.schemas._2067_3._2013.CompositionPlaylistType.EssenceDescriptorList buildEssenceDescriptorList(List<IMFEssenceDescriptorBaseType> imfEssenceDescriptorBaseTypeList){
        org.smpte_ra.schemas._2067_3._2013.CompositionPlaylistType.EssenceDescriptorList essenceDescriptorList = new org.smpte_ra.schemas._2067_3._2013.CompositionPlaylistType.EssenceDescriptorList();
        essenceDescriptorList.getEssenceDescriptor().addAll(imfEssenceDescriptorBaseTypeList.stream().map(e -> {
            org.smpte_ra.schemas._2067_3._2013.EssenceDescriptorBaseType essenceDescriptorBaseType = new org.smpte_ra.schemas._2067_3._2013.EssenceDescriptorBaseType();
            essenceDescriptorBaseType.setId(UUIDHelper.fromUUID(e.getId()));
            essenceDescriptorBaseType.getAny().addAll(e.getAny());
            return essenceDescriptorBaseType;
        }).collect(Collectors.toList()));
        return essenceDescriptorList;
    }

    /**
     * A method to construct a CompositionTimecodeType conforming to the 2013 schema
     * @param compositionEditRate the EditRate corresponding to the Composition's EditRate
     * @return a CompositionTimecodeType conforming to the 2013 schema
     */
    public org.smpte_ra.schemas._2067_3._2013.CompositionTimecodeType buildCompositionTimeCode(BigInteger compositionEditRate){
        org.smpte_ra.schemas._2067_3._2013.CompositionTimecodeType compositionTimecodeType = new CompositionTimecodeType();
        compositionTimecodeType.setTimecodeDropFrame(false);/*TimecodeDropFrame set to false by default*/
        compositionTimecodeType.setTimecodeRate(compositionEditRate);
        compositionTimecodeType.setTimecodeStartAddress(IMFUtils.generateTimecodeStartAddress());
        return compositionTimecodeType;
    }


    /**
     * A method to construct a LocaleType conforming to the 2013 schema
     * @param annotationText for the localeType
     * @param languages a list of string representing Language Tags as specified in RFC-5646
     * @param regions a list of strings representing regions
     * @param contentMaturityRatings a list of ContentMaturityRating objects conforming to the 2013 schema
     * @return a LocaleType object conforming to the 2013 schema
     */
    public org.smpte_ra.schemas._2067_3._2013.LocaleType buildLocaleType(org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType annotationText,
                                                                         List<String> languages,
                                                                         List<String> regions,
                                                                         List<org.smpte_ra.schemas._2067_3._2013.ContentMaturityRatingType> contentMaturityRatings){
        org.smpte_ra.schemas._2067_3._2013.LocaleType localeType = new org.smpte_ra.schemas._2067_3._2013.LocaleType();
        localeType.setAnnotation(annotationText);
        org.smpte_ra.schemas._2067_3._2013.LocaleType.LanguageList languageList = new org.smpte_ra.schemas._2067_3._2013.LocaleType.LanguageList();
        languageList.getLanguage().addAll(languages);
        localeType.setLanguageList(languageList);
        org.smpte_ra.schemas._2067_3._2013.LocaleType.RegionList regionList = new org.smpte_ra.schemas._2067_3._2013.LocaleType.RegionList();
        regionList.getRegion().addAll(regions);
        localeType.setRegionList(regionList);
        org.smpte_ra.schemas._2067_3._2013.LocaleType.ContentMaturityRatingList contentMaturityRatingList = new org.smpte_ra.schemas._2067_3._2013.LocaleType.ContentMaturityRatingList();
        contentMaturityRatingList.getContentMaturityRating().addAll(contentMaturityRatings);
        return localeType;
    }

    /**
     * A method to construct a ContentMaturityRatingType conforming to the 2013 schema
     * @param agency a string representing the agency that issued the rating for this Composition
     * @param rating a human-readable representation of the rating of this Composition
     * @param audience a human-readable representation of the intended target audience of this Composition
     * @return a ContentMaturityRating object conforming to the 2013 schema
     * @throws URISyntaxException any syntax errors with the agency attribute is exposed through a URISyntaxException
     */
    public org.smpte_ra.schemas._2067_3._2013.ContentMaturityRatingType buildContentMaturityRatingType(String agency, String rating, org.smpte_ra.schemas._2067_3._2013.ContentMaturityRatingType.Audience audience) throws URISyntaxException {
        org.smpte_ra.schemas._2067_3._2013.ContentMaturityRatingType contentMaturityRatingType = new org.smpte_ra.schemas._2067_3._2013.ContentMaturityRatingType();
        if(!agency.matches("^[a-zA-Z0-9._-]+") == true) {
            //this.imfErrorLogger.addError(new ErrorLogger.ErrorObject(IMFErrorLogger.IMFErrors.ErrorCodes.IMF_CPL_ERROR, IMFErrorLogger.IMFErrors.ErrorLevels.NON_FATAL, String.format("The ContentKind scope %s does not follow the syntax of a valid URI (a-z, A-Z, 0-9, ., _, -)", id)));
            throw new URISyntaxException("Invalid URI", "The ContentMaturityRating agency %s does not follow the syntax of a valid URI (a-z, A-Z, 0-9, ., _, -)");
        }
        contentMaturityRatingType.setAgency(agency);
        contentMaturityRatingType.setRating(rating);
        contentMaturityRatingType.setAudience(audience);
        return contentMaturityRatingType;
    }

    /**
     * A method to construct a SegmentType conforming to the 2013 schema
     * @param id a uuid identifying the segment
     * @param annotationText a human readable annotation describing the Segment conforming to the 2013 schema
     * @return a SegmentType conforming to the 2013 schema
     */
    public org.smpte_ra.schemas._2067_3._2013.SegmentType buildSegment(UUID id,
                                                                       org.smpte_ra.schemas._433._2008.dcmltypes.UserTextType annotationText){
        org.smpte_ra.schemas._2067_3._2013.SegmentType segment = new org.smpte_ra.schemas._2067_3._2013.SegmentType();
        segment.setId(UUIDHelper.fromUUID(uuid));
        segment.setAnnotation(annotationText);
        org.smpte_ra.schemas._2067_3._2013.SegmentType.SequenceList sequenceList = new org.smpte_ra.schemas._2067_3._2013.SegmentType.SequenceList();
        segment.setSequenceList(sequenceList);
        this.segments.add(segment);
        return segment;
    }

    /**
     * A method to construct a SegmentList conforming to the 2013 schema
     * @param segments a list of Segments conforming to the 2013 schema
     * @return a SegmentList conforming to the 2013 schema
     */
    public org.smpte_ra.schemas._2067_3._2013.CompositionPlaylistType.SegmentList buildSegmentList(List<org.smpte_ra.schemas._2067_3._2013.SegmentType> segments){
        org.smpte_ra.schemas._2067_3._2013.CompositionPlaylistType.SegmentList segmentList = new org.smpte_ra.schemas._2067_3._2013.CompositionPlaylistType.SegmentList();
        segmentList.getSegment().addAll(segments);
        return segmentList;
    }

    /**
     * A method to construct a SequenceTypeTuple object that maintains a reference to the SequenceType object conforming to the 2013 schema
     * and the type of the sequence
     * @param id a uuid identifying the sequence
     * @param trackId a uuid identifying the virtual track to which this SequenceBelongs. Note : This Id should remain constant across Segments for
     *                a Sequence that belongs to a VirtualTrack, please see the definition of a TrackId in st2067-3:2013
     * @param resourceList a list of resources corresponding to this Sequence
     * @param sequenceType an enumeration identifying the contents of this Sequence (Currently we only support serializing
     *                     MainImageSequence and MainAudioSequence to a CPL)
     * @return a SequenceTypeTuple that maintains a reference to a Sequence and its type
     */
    public SequenceTypeTuple buildSequenceTypeTuple(UUID id,
                                                    UUID trackId,
                                                    org.smpte_ra.schemas._2067_3._2013.SequenceType.ResourceList resourceList,
                                                    Composition.SequenceTypeEnum sequenceType){
        org.smpte_ra.schemas._2067_3._2013.SequenceType sequence = new org.smpte_ra.schemas._2067_3._2013.SequenceType();
        sequence.setId(UUIDHelper.fromUUID(id));
        sequence.setTrackId(UUIDHelper.fromUUID(trackId));
        sequence.setResourceList(resourceList);
        return new SequenceTypeTuple(sequence, sequenceType);
    }

    /**
     * A method to construct a ResourceList for a Sequence conforming to the 2013 schema
     * @param trackResourceList a list of BaseResourceTypes
     * @return a resource list conforming to the 2013 schema
     */
    public org.smpte_ra.schemas._2067_3._2013.SequenceType.ResourceList buildResourceList(List<org.smpte_ra.schemas._2067_3._2013.BaseResourceType> trackResourceList){
        org.smpte_ra.schemas._2067_3._2013.SequenceType.ResourceList resourceList = new org.smpte_ra.schemas._2067_3._2013.SequenceType.ResourceList();
        resourceList.getResource().addAll(trackResourceList);
        return resourceList;
    }

    /**
     * A method to populate the SequenceList of a VirtualTrack segment
     * @param sequenceTypeTuples a SequenceTypeTuple that maintains a reference to a Sequence and its type. This type is
     *                           deliberately an opaque object whose definition is not known outside this builder. This
     *                           is done in order to allow robust construction of a SequenceList serially.
     * @param segment a VirtualTrack Segment conforming to the 2013 schema
     */
    public void populateSequenceListForSegment(List<SequenceTypeTuple> sequenceTypeTuples,
                                               org.smpte_ra.schemas._2067_3._2013.SegmentType segment) {

        org.smpte_ra.schemas._2067_2._2013.ObjectFactory objectFactory = new org.smpte_ra.schemas._2067_2._2013.ObjectFactory();
        List<Object> any = segment.getSequenceList().getAny();

        for(SequenceTypeTuple sequenceTypeTuple : sequenceTypeTuples){
            switch(sequenceTypeTuple.getSequenceType()){
                case MainImageSequence:
                    any.add(objectFactory.createMainImageSequence(sequenceTypeTuple.getSequence()));
                    break;
                case MainAudioSequence:
                    any.add(objectFactory.createMainAudioSequence(sequenceTypeTuple.getSequence()));
                    break;
                case MarkerSequence:
                    segment.getSequenceList().setMarkerSequence(sequenceTypeTuple.getSequence());
                    break;
                default:
                    throw new IMFAuthoringException(String.format("Currently we only support %s, %s, and %s sequence types in building a Composition Playlist document, the type of sequence being requested is %s",
                            Composition.SequenceTypeEnum.MainAudioSequence, Composition.SequenceTypeEnum.MainImageSequence, Composition.SequenceTypeEnum.MarkerSequence, sequenceTypeTuple.getSequenceType()));
            }
        }
    }

    /**
     * A method to construct a TrackFileResourceType conforming to the 2013 schema
     * @param trackResource an object that roughly models a TrackFileResourceType
     * @return a BaseResourceType conforming to the 2013 schema
     */
    public org.smpte_ra.schemas._2067_3._2013.BaseResourceType buildTrackFileResource(IMFTrackFileResourceType trackResource){
        org.smpte_ra.schemas._2067_3._2013.TrackFileResourceType trackFileResource = new org.smpte_ra.schemas._2067_3._2013.TrackFileResourceType();
        trackFileResource.setId(trackResource.getId());
        trackFileResource.setAnnotation(null);
        trackFileResource.setTrackFileId(trackResource.getTrackFileId());
        trackFileResource.getEditRate().add(trackResource.getEditRate().getNumerator());
        trackFileResource.getEditRate().add(trackResource.getEditRate().getDenominator());
        trackFileResource.setIntrinsicDuration(trackResource.getIntrinsicDuration());
        trackFileResource.setEntryPoint(trackResource.getEntryPoint());
        trackFileResource.setSourceDuration(trackResource.getSourceDuration());
        trackFileResource.setRepeatCount(trackResource.getRepeatCount());
        trackFileResource.setSourceEncoding(UUIDHelper.fromUUID(this.trackResourceSourceEncodingMap.get(UUIDHelper.fromUUIDAsURNStringToUUID(trackResource.getTrackFileId()))));
        trackFileResource.setHash(trackResource.getHash());

        return trackFileResource;
    }

    /**
     * A method to construct a MarkerResourceType conforming to the 2013 schema
     * @param markerResource an object that roughly models a MarkerResourceType
     * @return a MarkerResourceType conforming to the 2013 schema
     */
    public org.smpte_ra.schemas._2067_3._2013.MarkerResourceType buildMarkerResource(IMFMarkerResourceType markerResource){
        org.smpte_ra.schemas._2067_3._2013.MarkerResourceType jaxbMarkerResource = new org.smpte_ra.schemas._2067_3._2013.MarkerResourceType();
        jaxbMarkerResource.setId(markerResource.getId());
        jaxbMarkerResource.setAnnotation(null);
        jaxbMarkerResource.getEditRate().add(markerResource.getEditRate().getNumerator());
        jaxbMarkerResource.getEditRate().add(markerResource.getEditRate().getDenominator());
        jaxbMarkerResource.setIntrinsicDuration(markerResource.getIntrinsicDuration());
        jaxbMarkerResource.setEntryPoint(markerResource.getEntryPoint());
        jaxbMarkerResource.setSourceDuration(markerResource.getSourceDuration());
        jaxbMarkerResource.setRepeatCount(markerResource.getRepeatCount());

        for(IMFMarkerType marker : markerResource.getMarkerList()) {
            // Wrap each Marker into the JAXB class
            org.smpte_ra.schemas._2067_3._2013.MarkerType jaxbMarker = new org.smpte_ra.schemas._2067_3._2013.MarkerType();
            jaxbMarker.setOffset(marker.getOffset());
            if (marker.getAnnotation() != null) {
                jaxbMarker.setAnnotation(buildCPLUserTextType_2013(marker.getAnnotation(), null));
            }
            org.smpte_ra.schemas._2067_3._2013.MarkerType.Label jaxbLabel = new org.smpte_ra.schemas._2067_3._2013.MarkerType.Label();
            jaxbLabel.setValue(marker.getLabel().getValue());
            if (marker.getLabel().getScope() != null) {
                jaxbLabel.setScope(marker.getLabel().getScope());
            }
            jaxbMarker.setLabel(jaxbLabel);

            // Add each marker to the list in marker resource
            jaxbMarkerResource.getMarker().add(jaxbMarker);
        }

        return jaxbMarkerResource;
    }

    /**
     * A method to construct a Default Digest Method Type with a default HashAlgorithm
     * @return a DigestMethodType object conforming to the 2013 schema with the default HashAlgorithm
     */
    public org.w3._2000._09.xmldsig_.DigestMethodType buildDefaultDigestMethodType(){
        org.w3._2000._09.xmldsig_.DigestMethodType digestMethodType = new org.w3._2000._09.xmldsig_.DigestMethodType();
        digestMethodType.setAlgorithm(CompositionPlaylistBuilder_2013.defaultHashAlgorithm);
        return digestMethodType;
    }

    /**
     * A method to construct a Digest Method Type with the HashAlgorithm string that was passed in
     * @param algorithm a String representing the alogrithm used for generating the Hash
     * @return a DigestMethodType object conforming to the 2016 schema with the default HashAlgorithm
     */
    public org.w3._2000._09.xmldsig_.DigestMethodType buildDigestMethodType(String algorithm){
        org.w3._2000._09.xmldsig_.DigestMethodType digestMethodType = new org.w3._2000._09.xmldsig_.DigestMethodType();
        digestMethodType.setAlgorithm(algorithm);
        return digestMethodType;
    }

    /**
     * Getter for the errors in CompositionPlaylistBuilder_2013
     *
     * @return List of errors in CompositionPlaylistBuilder_2013.
     */
    public List<ErrorLogger.ErrorObject> getErrors() {
        return imfErrorLogger.getErrors();
    }


    /**
     * A thin class that maintains a reference to a VirtualTrack Sequence object and the type of the Sequence.
     * Its state is opaque to classes outside this builder
     */
    public static class SequenceTypeTuple{
        private final org.smpte_ra.schemas._2067_3._2013.SequenceType sequence;
        private final Composition.SequenceTypeEnum sequenceType;

        private SequenceTypeTuple(org.smpte_ra.schemas._2067_3._2013.SequenceType sequence, Composition.SequenceTypeEnum sequenceType){
            this.sequence = sequence;
            this.sequenceType = sequenceType;
        }

        private org.smpte_ra.schemas._2067_3._2013.SequenceType getSequence(){
            return this.sequence;
        }

        private Composition.SequenceTypeEnum getSequenceType(){
            return this.sequenceType;
        }
    }

    /**
     * Getter for the CPL file name for the Composition
     *
     * @return CPL file name for the Composition.
     */
    public String getCPLFileName() {
        return this.cplFileName;
    }

}
