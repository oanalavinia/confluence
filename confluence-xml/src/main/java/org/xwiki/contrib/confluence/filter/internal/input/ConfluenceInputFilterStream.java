/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.confluence.filter.internal.input;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.confluence.filter.input.ConfluenceInputContext;
import org.xwiki.contrib.confluence.filter.input.ConfluenceInputProperties;
import org.xwiki.contrib.confluence.filter.internal.ConfluenceFilter;
import org.xwiki.contrib.confluence.filter.internal.ConfluenceProperties;
import org.xwiki.contrib.confluence.filter.internal.ConfluenceXMLPackage;
import org.xwiki.contrib.confluence.parser.confluence.internal.ConfluenceParser;
import org.xwiki.contrib.confluence.parser.xhtml.ConfluenceXHTMLInputProperties;
import org.xwiki.contrib.confluence.parser.xhtml.internal.ConfluenceXHTMLParser;
import org.xwiki.contrib.confluence.parser.xhtml.internal.InternalConfluenceXHTMLInputProperties;
import org.xwiki.environment.Environment;
import org.xwiki.filter.FilterEventParameters;
import org.xwiki.filter.FilterException;
import org.xwiki.filter.event.model.WikiAttachmentFilter;
import org.xwiki.filter.event.model.WikiDocumentFilter;
import org.xwiki.filter.event.model.WikiObjectFilter;
import org.xwiki.filter.event.user.GroupFilter;
import org.xwiki.filter.event.user.UserFilter;
import org.xwiki.filter.input.AbstractBeanInputFilterStream;
import org.xwiki.filter.input.BeanInputFilterStream;
import org.xwiki.filter.input.BeanInputFilterStreamFactory;
import org.xwiki.filter.input.InputFilterStreamFactory;
import org.xwiki.filter.input.StringInputSource;
import org.xwiki.job.event.status.JobProgressManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.rendering.listener.Listener;
import org.xwiki.rendering.parser.ParseException;
import org.xwiki.rendering.parser.StreamParser;
import org.xwiki.rendering.renderer.PrintRenderer;
import org.xwiki.rendering.renderer.PrintRendererFactory;
import org.xwiki.rendering.renderer.printer.DefaultWikiPrinter;
import org.xwiki.rendering.syntax.Syntax;

/**
 * @version $Id$
 * @since 9.0
 */
@Component
@Named(ConfluenceInputFilterStreamFactory.ROLEHINT)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class ConfluenceInputFilterStream
    extends AbstractBeanInputFilterStream<ConfluenceInputProperties, ConfluenceFilter>
{
    private final static Pattern FORBIDDEN_USER_CHARACTERS = Pattern.compile("[. /]");

    @Inject
    private Logger logger;

    @Inject
    @Named(ConfluenceParser.SYNTAX_STRING)
    private StreamParser confluenceWIKIParser;

    @Inject
    @Named(ConfluenceXHTMLParser.SYNTAX_STRING)
    private InputFilterStreamFactory confluenceXHTMLParserFactory;

    @Inject
    private Provider<ConfluenceConverterListener> converterProvider;

    @Inject
    private JobProgressManager progress;

    @Inject
    @Named("xwiki/2.1")
    private PrintRendererFactory xwiki21Factory;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    private Environment environment;

    @Inject
    private ConfluenceInputContext context;

    @Inject
    private XWikiConverter converter;

    private ConfluenceXMLPackage confluencePackage;

    @Override
    public void close() throws IOException
    {
        this.properties.getSource().close();
    }

    @Override
    protected void read(Object filter, ConfluenceFilter proxyFilter) throws FilterException
    {
        if (this.context instanceof DefaultConfluenceInputContext) {
            ((DefaultConfluenceInputContext) this.context).set(this.properties);
        }

        try {
            readInternal(filter, proxyFilter);
        } finally {
            if (this.context instanceof DefaultConfluenceInputContext) {
                ((DefaultConfluenceInputContext) this.context).remove();
            }
        }
    }

    private void readInternal(Object filter, ConfluenceFilter proxyFilter) throws FilterException
    {
        // Prepare package
        try {
            this.confluencePackage = new ConfluenceXMLPackage(this.properties.getSource(), this.environment);
        } catch (Exception e) {
            throw new FilterException("Failed to read package", e);
        }

        Map<Long, List<Long>> pages = this.confluencePackage.getPages();

        if (this.properties.isUsersEnabled()) {
            Collection<Long> users = this.confluencePackage.getInternalUsers();
            // TODO get users in new format (this.confluencePackage.getAllUsers())
            Collection<Long> groups = this.confluencePackage.getGroups();

            this.progress.pushLevelProgress(users.size() + groups.size() + pages.size()
                + pages.entrySet().stream().mapToInt(e -> e.getValue().size()).sum(), this);

            sendUsers(users, groups, proxyFilter);
        } else {
            this.progress.pushLevelProgress(
                pages.size() + pages.entrySet().stream().mapToInt(e -> e.getValue().size()).sum(), this);
        }

        // Generate documents events
        for (Map.Entry<Long, List<Long>> entry : pages.entrySet()) {
            long spaceId = entry.getKey();

            ConfluenceProperties spaceProperties;
            try {
                spaceProperties = this.confluencePackage.getSpaceProperties(spaceId);
            } catch (ConfigurationException e) {
                throw new FilterException("Failed to get space properties", e);
            }

            String spaceKey = ConfluenceXMLPackage.getSpaceKey(spaceProperties);

            // Apply the standard entity name validator
            if (this.properties.isConvertToXWiki() && this.properties.isEntityNameValidation()) {
                spaceKey = this.converter.convert(spaceKey);
            }

            FilterEventParameters spaceParameters = new FilterEventParameters();

            // > WikiSpace
            proxyFilter.beginWikiSpace(spaceKey, spaceParameters);

            // Main page
            Long descriptionId = spaceProperties.getLong(ConfluenceXMLPackage.KEY_SPACE_DESCRIPTION, null);
            if (descriptionId != null) {
                this.progress.startStep(this);
                if (this.properties.isIncluded(descriptionId)) {
                    readPage(descriptionId, spaceKey, filter, proxyFilter);
                }
                this.progress.endStep(this);
            }

            // Other pages
            for (long pageId : entry.getValue()) {
                this.progress.startStep(this);
                if (this.properties.isIncluded(pageId)) {
                    readPage(pageId, spaceKey, filter, proxyFilter);
                }
                this.progress.endStep(this);
            }

            // < WikiSpace
            proxyFilter.endWikiSpace(spaceKey, spaceParameters);
        }

        this.progress.popLevelProgress(this);

        // Cleanup

        try {
            this.confluencePackage.close();
        } catch (IOException e) {
            throw new FilterException("Failed to close package", e);
        }
    }

    private void sendUsers(Collection<Long> users, Collection<Long> groups, ConfluenceFilter proxyFilter)
        throws FilterException
    {
        // Switch the wiki if a specific one is forced
        if (this.properties.getUsersWiki() != null) {
            proxyFilter.beginWiki(this.properties.getUsersWiki(), FilterEventParameters.EMPTY);
        }

        // Generate users events
        for (Long userId : users) {
            this.progress.startStep(this);

            ConfluenceProperties userProperties;
            try {
                userProperties = this.confluencePackage.getInternalUserProperties(userId);
            } catch (ConfigurationException e) {
                throw new FilterException("Failed to get user properties", e);
            }

            String userName = toUserReferenceName(
                userProperties.getString(ConfluenceXMLPackage.KEY_USER_NAME, String.valueOf(userId)));

            FilterEventParameters userParameters = new FilterEventParameters();

            userParameters.put(UserFilter.PARAMETER_FIRSTNAME,
                userProperties.getString(ConfluenceXMLPackage.KEY_USER_FIRSTNAME));
            userParameters.put(UserFilter.PARAMETER_LASTNAME,
                userProperties.getString(ConfluenceXMLPackage.KEY_USER_LASTNAME));
            userParameters.put(UserFilter.PARAMETER_EMAIL,
                userProperties.getString(ConfluenceXMLPackage.KEY_USER_EMAIL));
            userParameters.put(UserFilter.PARAMETER_ACTIVE,
                userProperties.getBoolean(ConfluenceXMLPackage.KEY_USER_ACTIVE, true));

            try {
                userParameters.put(UserFilter.PARAMETER_REVISION_DATE,
                    this.confluencePackage.getDate(userProperties, ConfluenceXMLPackage.KEY_USER_REVISION_DATE));
                userParameters.put(UserFilter.PARAMETER_CREATION_DATE,
                    this.confluencePackage.getDate(userProperties, ConfluenceXMLPackage.KEY_USER_CREATION_DATE));
            } catch (Exception e) {
                if (this.properties.isVerbose()) {
                    this.logger.error("Failed to parse the user date", e);
                }
            }

            // TODO: no idea how to import/convert the password, probably salted with the Confluence instance id

            // > User
            proxyFilter.beginUser(userName, userParameters);

            // < User
            proxyFilter.endUser(userName, userParameters);

            this.progress.endStep(this);
        }

        // Generate groups events
        for (long groupInt : groups) {
            this.progress.startStep(this);

            ConfluenceProperties groupProperties;
            try {
                groupProperties = this.confluencePackage.getGroupProperties(groupInt);
            } catch (ConfigurationException e) {
                throw new FilterException("Failed to get group properties", e);
            }

            String groupName = groupProperties.getString(ConfluenceXMLPackage.KEY_GROUP_NAME, String.valueOf(groupInt));
            if (this.properties.isConvertToXWiki()) {
                if (groupName.equals("confluence-administrators")) {
                    groupName = "XWikiAdminGroup";
                } else if (groupName.equals("confluence-users")) {
                    groupName = "XWikiAllGroup";
                }
            }

            FilterEventParameters groupParameters = new FilterEventParameters();

            try {
                groupParameters.put(GroupFilter.PARAMETER_REVISION_DATE,
                    this.confluencePackage.getDate(groupProperties, ConfluenceXMLPackage.KEY_GROUP_REVISION_DATE));
                groupParameters.put(GroupFilter.PARAMETER_CREATION_DATE,
                    this.confluencePackage.getDate(groupProperties, ConfluenceXMLPackage.KEY_GROUP_CREATION_DATE));
            } catch (Exception e) {
                if (this.properties.isVerbose()) {
                    this.logger.error("Failed to parse the group date", e);
                }
            }

            // > Group
            proxyFilter.beginGroupContainer(groupName, groupParameters);

            // Members users
            if (groupProperties.containsKey(ConfluenceXMLPackage.KEY_GROUP_MEMBERUSERS)) {
                List<Long> groupMembers =
                    this.confluencePackage.getLongList(groupProperties, ConfluenceXMLPackage.KEY_GROUP_MEMBERUSERS);
                for (Long memberInt : groupMembers) {
                    FilterEventParameters memberParameters = new FilterEventParameters();

                    try {
                        String memberId = this.confluencePackage.getInternalUserProperties(memberInt)
                            .getString(ConfluenceXMLPackage.KEY_USER_NAME, String.valueOf(memberInt));

                        if (this.properties.isConvertToXWiki() && memberId.equals("admin")) {
                            memberId = "Admin";
                        }

                        proxyFilter.onGroupMemberGroup(memberId, memberParameters);
                    } catch (Exception e) {
                        this.logger.error("Failed to get user properties", e);
                    }
                }
            }

            // Members groups
            if (groupProperties.containsKey(ConfluenceXMLPackage.KEY_GROUP_MEMBERGROUPS)) {
                List<Long> groupMembers =
                    this.confluencePackage.getLongList(groupProperties, ConfluenceXMLPackage.KEY_GROUP_MEMBERGROUPS);
                for (Long memberInt : groupMembers) {
                    FilterEventParameters memberParameters = new FilterEventParameters();

                    try {
                        String memberId = this.confluencePackage.getGroupProperties(memberInt)
                            .getString(ConfluenceXMLPackage.KEY_GROUP_NAME, String.valueOf(memberInt));

                        if (this.properties.isConvertToXWiki()) {
                            if (memberId.equals("confluence-administrators")) {
                                memberId = "XWikiAdminGroup";
                            } else if (memberId.equals("confluence-users")) {
                                memberId = "XWikiAllGroup";
                            }
                        }

                        proxyFilter.onGroupMemberGroup(memberId, memberParameters);
                    } catch (Exception e) {
                        this.logger.error("Failed to get group properties", e);
                    }
                }
            }

            // < Group
            proxyFilter.endGroupContainer(groupName, groupParameters);

            this.progress.endStep(this);
        }

        // Get back to default wiki
        if (this.properties.getUsersWiki() != null) {
            proxyFilter.endWiki(this.properties.getUsersWiki(), FilterEventParameters.EMPTY);
        }
    }

    private void readPage(long pageId, String spaceKey, Object filter, ConfluenceFilter proxyFilter)
        throws FilterException
    {
        ConfluenceProperties pageProperties = getPageProperties(pageId);

        if (pageProperties == null) {
            this.logger.warn("Can't find page with id [{}]", pageId);

            return;
        }

        String documentName;
        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_HOMEPAGE)) {
            documentName = this.properties.getSpacePageName();
        } else {
            documentName = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_TITLE);
        }

        // Skip pages with empty title
        if (StringUtils.isEmpty(documentName)) {
            this.logger.warn("Found a page without a name or title (id={}). Skipping it.", pageId);

            return;
        }

        // Skip deleted, archived or draft pages
        String contentStatus = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_CONTENT_STATUS);
        if (contentStatus != null
            && (contentStatus.equals("deleted") || contentStatus.equals("archived") || contentStatus.equals("draft")))
            return;

        FilterEventParameters documentParameters = new FilterEventParameters();
        if (this.properties.getDefaultLocale() != null) {
            documentParameters.put(WikiDocumentFilter.PARAMETER_LOCALE, this.properties.getDefaultLocale());
        }

        // Apply the standard entity name validator
        if (this.properties.isConvertToXWiki() && this.properties.isEntityNameValidation()) {
            documentName = this.converter.convert(documentName);
        }

        // > WikiDocument
        proxyFilter.beginWikiDocument(documentName, documentParameters);

        Locale locale = Locale.ROOT;

        FilterEventParameters documentLocaleParameters = new FilterEventParameters();
        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_CREATION_AUTHOR)) {
            documentLocaleParameters.put(WikiDocumentFilter.PARAMETER_CREATION_AUTHOR,
                toUserReference(pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_CREATION_AUTHOR)));
        } else if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_CREATION_AUTHOR_KEY)) {
            String authorKey = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_CREATION_AUTHOR_KEY);
            String authorName = toUserReference(resolveUserName(authorKey, authorKey));
            documentLocaleParameters.put(WikiDocumentFilter.PARAMETER_CREATION_AUTHOR, authorName);
        }

        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_CREATION_DATE)) {
            try {
                documentLocaleParameters.put(WikiDocumentFilter.PARAMETER_CREATION_DATE,
                    this.confluencePackage.getDate(pageProperties, ConfluenceXMLPackage.KEY_PAGE_CREATION_DATE));
            } catch (Exception e) {
                if (this.properties.isVerbose()) {
                    this.logger.error("Failed to parse date", e);
                }
            }
        }
        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_REVISION)) {
            documentLocaleParameters.put(WikiDocumentFilter.PARAMETER_LASTREVISION,
                pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_REVISION));
        }

        // > WikiDocumentLocale
        proxyFilter.beginWikiDocumentLocale(locale, documentLocaleParameters);

        // Revisions
        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_REVISIONS)) {
            List<Long> revisions =
                this.confluencePackage.getLongList(pageProperties, ConfluenceXMLPackage.KEY_PAGE_REVISIONS);
            for (Long revisionId : revisions) {
                readPageRevision(revisionId, spaceKey, filter, proxyFilter);
            }
        }

        // Current version
        readPageRevision(pageId, spaceKey, filter, proxyFilter);

        // < WikiDocumentLocale
        proxyFilter.endWikiDocumentLocale(locale, documentLocaleParameters);

        // < WikiDocument
        proxyFilter.endWikiDocument(documentName, documentParameters);
    }

    String resolveUserName(String key, String def)
    {
        try {
            ConfluenceProperties userProperties = this.confluencePackage.getUserProperties(key);

            if (userProperties != null) {
                String userName = userProperties.getString(ConfluenceXMLPackage.KEY_USER_NAME);

                if (userName != null) {
                    return userName;
                }
            }
        } catch (ConfigurationException e) {
            this.logger.warn("Failed to retrieve properties of user with key [{}]: {}", key,
                ExceptionUtils.getRootCauseMessage(e));
        }

        return def;
    }

    String toMappedUser(String confluenceUser)
    {
        if (this.properties.getUserIdMapping() != null) {
            String mappedName = this.properties.getUserIdMapping().get(confluenceUser);

            if (mappedName != null) {
                mappedName = mappedName.trim();

                if (!mappedName.isEmpty()) {
                    return mappedName;
                }
            }
        }

        return confluenceUser;
    }

    String toUserReferenceName(String userName)
    {
        if (userName == null || !this.properties.isConvertToXWiki()) {
            // Apply the configured mapping
            return toMappedUser(userName);
        }

        // Translate the usual default admin user in Confluence to it's XWiki counterpart
        if (userName.equals("admin")) {
            return "Admin";
        }

        // Apply the configured mapping
        userName = toMappedUser(userName);

        // Protected from characters not well supported in user page name depending on the version of XWiki
        userName = FORBIDDEN_USER_CHARACTERS.matcher(userName).replaceAll("_");

        return userName;
    }

    String toUserReference(String userName)
    {
        if (userName == null || !this.properties.isConvertToXWiki()) {
            return userName;
        }

        // Transform user name according to configuration
        userName = toUserReferenceName(userName);

        // Add the "XWiki" space and the wiki if configured. Ideally this should probably be done on XWiki Instance
        // Output filter side
        EntityReference reference;
        if (this.properties.getUsersWiki() != null) {
            reference = new DocumentReference(this.properties.getUsersWiki(), "XWiki", userName);
        } else {
            reference = new LocalDocumentReference("XWiki", userName);
        }

        return this.serializer.serialize(reference);
    }

    private ConfluenceProperties getPageProperties(Long pageId) throws FilterException
    {
        try {
            return this.confluencePackage.getPageProperties(pageId, false);
        } catch (ConfigurationException e) {
            throw new FilterException("Failed to get page properties", e);
        }
    }

    private void readPageRevision(Long pageId, String spaceKey, Object filter, ConfluenceFilter proxyFilter)
        throws FilterException
    {
        ConfluenceProperties pageProperties = getPageProperties(pageId);

        if (pageProperties == null) {
            this.logger.warn("Can't find page revision with id [{}]", pageId);
            return;
        }

        readPageRevision(pageId, spaceKey, pageProperties, filter, proxyFilter);
    }

    private void readPageRevision(long pageId, String spaceKey, ConfluenceProperties pageProperties, Object filter,
        ConfluenceFilter proxyFilter) throws FilterException
    {
        String revision = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_REVISION);

        FilterEventParameters documentRevisionParameters = new FilterEventParameters();
        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_PARENT)) {
            try {
                documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_PARENT,
                    this.confluencePackage.getReferenceFromId(pageProperties, ConfluenceXMLPackage.KEY_PAGE_PARENT));
            } catch (Exception e) {
                if (this.properties.isVerbose()) {
                    this.logger.error("Failed to parse parent", e);
                }
            }
        }
        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_REVISION_AUTHOR)) {
            documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_REVISION_AUTHOR,
                toUserReference(pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_REVISION_AUTHOR)));
        } else if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_REVISION_AUTHOR_KEY)) {
            String authorKey = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_REVISION_AUTHOR_KEY);
            String authorName = toUserReference(resolveUserName(authorKey, authorKey));
            documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_REVISION_AUTHOR, authorName);
        }
        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_REVISION_DATE)) {
            try {
                documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_REVISION_DATE,
                    this.confluencePackage.getDate(pageProperties, ConfluenceXMLPackage.KEY_PAGE_REVISION_DATE));
            } catch (Exception e) {
                if (this.properties.isVerbose()) {
                    this.logger.error("Failed to parse date", e);
                }
            }
        }
        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_REVISION_COMMENT)) {
            documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_REVISION_COMMENT,
                pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_REVISION_COMMENT));
        }
        documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_TITLE,
            pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_TITLE));

        String bodyContent = null;
        Syntax bodySyntax = null;
        int bodyType = -1;

        if (pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_BODY)) {
            bodyContent = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_BODY);
            bodyType = pageProperties.getInt(ConfluenceXMLPackage.KEY_PAGE_BODY_TYPE, -1);

            switch (bodyType) {
                // Not bodyType means old Confluence syntax
                case -1:
                    bodyType = 0;
                case 0:
                    bodySyntax = ConfluenceParser.SYNTAX;
                    break;
                case 2:
                    bodySyntax = Syntax.CONFLUENCEXHTML_1_0;
                    break;
                default:
                    if (this.properties.isVerbose()) {
                        this.logger.error("Unknown body type [{}]", bodyType);
                    }
                    break;
            }
        }

        // Content
        if (bodyContent != null) {
            if (this.properties.isContentEvents() && filter instanceof Listener) {
                // > WikiDocumentRevision
                proxyFilter.beginWikiDocumentRevision(revision, documentRevisionParameters);

                try {
                    parse(bodyContent, bodyType, this.properties.getMacroContentSyntax(), proxyFilter);
                } catch (Exception e) {
                    this.logger.error("Failed to parse content of page with id [{}]", pageId, e);
                }
            } else if (this.properties.isConvertToXWiki()) {
                // Convert content to XWiki syntax
                try {
                    documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_CONTENT,
                        convertToXWiki21(bodyContent, bodyType));
                    documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_SYNTAX, Syntax.XWIKI_2_1);
                } catch (Exception e) {
                    this.logger.error("Failed to convert content of the page with id [{}]", pageId, e);
                }

                // > WikiDocumentRevision
                proxyFilter.beginWikiDocumentRevision(revision, documentRevisionParameters);
            } else {
                // Keep Confluence syntax
                documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_CONTENT, bodyContent);
                documentRevisionParameters.put(WikiDocumentFilter.PARAMETER_SYNTAX, bodySyntax);

                // > WikiDocumentRevision
                proxyFilter.beginWikiDocumentRevision(revision, documentRevisionParameters);
            }
        } else {
            // > WikiDocumentRevision
            proxyFilter.beginWikiDocumentRevision(revision, documentRevisionParameters);
        }

        // Attachments
        Map<String, ConfluenceProperties> pageAttachments = new LinkedHashMap<>();
        for (long attachmentId : this.confluencePackage.getAttachments(pageId)) {
            ConfluenceProperties attachmentProperties;
            try {
                attachmentProperties = this.confluencePackage.getAttachmentProperties(pageId, attachmentId);
            } catch (ConfigurationException e) {
                throw new FilterException("Failed to get attachment properties", e);
            }

            String attachmentName = this.confluencePackage.getAttachmentName(attachmentProperties);

            ConfluenceProperties currentAttachmentProperties = pageAttachments.get(attachmentName);
            if (currentAttachmentProperties != null) {
                try {
                    Date date = this.confluencePackage.getDate(attachmentProperties,
                        ConfluenceXMLPackage.KEY_ATTACHMENT_REVISION_DATE);
                    Date currentDate = this.confluencePackage.getDate(currentAttachmentProperties,
                        ConfluenceXMLPackage.KEY_ATTACHMENT_REVISION_DATE);

                    if (date.after(currentDate)) {
                        pageAttachments.put(attachmentName, attachmentProperties);
                    }
                } catch (Exception e) {
                    this.logger.warn("Failed to parse the date of attachment with id [{}], skipping it", attachmentId,
                        e);
                }
            } else {
                pageAttachments.put(attachmentName, attachmentProperties);
            }
        }

        for (ConfluenceProperties attachmentProperties : pageAttachments.values()) {
            readAttachment(pageId, attachmentProperties, filter, proxyFilter);
        }

        // Tags
        Map<String, ConfluenceProperties> pageTags = new LinkedHashMap<>();
        for (Object tagIdStringObject : pageProperties.getList(ConfluenceXMLPackage.KEY_PAGE_LABELLINGS)) {
            long tagId = Long.valueOf((String) tagIdStringObject);
            ConfluenceProperties tagProperties;
            try {
                tagProperties = this.confluencePackage.getObjectProperties(tagId);
            } catch (ConfigurationException e) {
                throw new FilterException("Failed to get tag properties", e);
            }

            String tagName = this.confluencePackage.getTagName(tagProperties);
            pageTags.put(tagName, tagProperties);
        }

        if (!pageTags.isEmpty()) {
            readPageTags(pageProperties, proxyFilter, pageTags);
        }

        // Comments
        Map<Long, ConfluenceProperties> pageComments = new LinkedHashMap<>();
        Map<Long, Integer> commentIndeces = new LinkedHashMap<>();
        int commentIndex = 0;
        for (Object commentIdStringObject : pageProperties.getList(ConfluenceXMLPackage.KEY_PAGE_COMMENTS)) {
            long commentId = Long.parseLong((String) commentIdStringObject);
            ConfluenceProperties commentProperties;
            try {
                commentProperties = this.confluencePackage.getObjectProperties(commentId);
            } catch (ConfigurationException e) {
                throw new FilterException("Failed to get comment properties", e);
            }

            pageComments.put(commentId, commentProperties);
            commentIndeces.put(commentId, commentIndex);
            commentIndex++;
        }

        for (Long commentId : pageComments.keySet()) {
            readPageComment(pageProperties, proxyFilter, commentId, pageComments, commentIndeces);
        }

        if (this.properties.isStoreConfluenceDetailsEnabled()) {
            storeConfluenceDetails(pageId, spaceKey, pageProperties, proxyFilter);
        }

        // < WikiDocumentRevision
        proxyFilter.endWikiDocumentRevision(revision, documentRevisionParameters);
    }

    /**
     * @since 9.13
     */
    private void storeConfluenceDetails(long pageId, String spaceKey, ConfluenceProperties pageProperties,
        ConfluenceFilter proxyFilter) throws FilterException
    {
        FilterEventParameters pageReportParameters = new FilterEventParameters();
        String objectName = getObjectName(pageProperties);

        // Page report object
        pageReportParameters.put(WikiObjectFilter.PARAMETER_NUMBER, 0);
        pageReportParameters.put(WikiObjectFilter.PARAMETER_CLASS_REFERENCE, "Confluence.Code.ConfluencePageClass");
        proxyFilter.beginWikiObject(objectName, pageReportParameters);

        StringBuilder pageURLBuilder = new StringBuilder();
        if (this.properties.getBaseURLs() != null) {
            pageURLBuilder.append(this.properties.getBaseURLs().get(0).toString());
            pageURLBuilder.append("/wiki/spaces/").append(spaceKey);
            if (!pageProperties.containsKey(ConfluenceXMLPackage.KEY_PAGE_HOMEPAGE)) {
                String pageName = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_TITLE);
                pageURLBuilder.append("/pages/").append(pageId).append("/").append(pageName);
            }
        }

        proxyFilter.onWikiObjectProperty("id", pageId, new FilterEventParameters());
        proxyFilter.onWikiObjectProperty("url", pageURLBuilder.toString(), new FilterEventParameters());
        proxyFilter.onWikiObjectProperty("space", spaceKey, new FilterEventParameters());

        proxyFilter.endWikiObject(objectName, pageReportParameters);
    }

    private String convertToXWiki21(String bodyContent, int bodyType) throws FilterException, ParseException
    {
        DefaultWikiPrinter printer = new DefaultWikiPrinter();
        PrintRenderer renderer = this.xwiki21Factory.createRenderer(printer);

        parse(bodyContent, bodyType, Syntax.XWIKI_2_1, renderer);

        return printer.toString();
    }

    private ConfluenceConverterListener createConverter(Listener listener)
    {
        ConfluenceConverterListener converter = this.converterProvider.get();
        converter.initialize(this.confluencePackage, this, this.properties);
        converter.setWrappedListener(listener);

        return converter;
    }

    private Listener wrap(Listener listener)
    {
        if (this.properties.isConvertToXWiki()) {
            return createConverter(listener);
        }

        return listener;
    }

    private void parse(String bodyContent, int bodyType, Syntax macroContentSyntax, Listener listener)
        throws FilterException, ParseException
    {
        switch (bodyType) {
            case 0:
                this.confluenceWIKIParser.parse(new StringReader(bodyContent), wrap(listener));
                break;
            case 2:
                createSyntaxFilter(bodyContent, macroContentSyntax).read(listener);
                break;
            default:
                break;
        }
    }

    private BeanInputFilterStream<ConfluenceXHTMLInputProperties> createSyntaxFilter(String bodyContent,
        Syntax macroContentSyntax) throws FilterException
    {
        InternalConfluenceXHTMLInputProperties filterProperties = new InternalConfluenceXHTMLInputProperties();
        filterProperties.setSource(new StringInputSource(bodyContent));
        filterProperties.setMacroContentSyntax(macroContentSyntax);

        if (this.properties.isConvertToXWiki()) {
            filterProperties.setConverter(createConverter(null));
        }

        BeanInputFilterStreamFactory<ConfluenceXHTMLInputProperties> syntaxFilterFactory =
            ((BeanInputFilterStreamFactory<ConfluenceXHTMLInputProperties>) this.confluenceXHTMLParserFactory);

        return syntaxFilterFactory.createInputFilterStream(filterProperties);
    }

    private void readAttachment(long pageId, ConfluenceProperties attachmentProperties, Object filter,
        ConfluenceFilter proxyFilter) throws FilterException
    {
        String contentStatus = attachmentProperties.getString(ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENTSTATUS, null);
        if (StringUtils.equals(contentStatus, "deleted")) {
            // The actual deleted attachment is not in the exported package so we can't really do anything with it
            return;
        }

        long attachmentId = attachmentProperties.getLong("id");

        String attachmentName = this.confluencePackage.getAttachmentName(attachmentProperties);

        long attachmentSize;
        String mediaType = null;
        if (attachmentProperties.containsKey(ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENTPROPERTIES)) {
            ConfluenceProperties attachmentContentProperties =
                getContentProperties(attachmentProperties, ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENTPROPERTIES);

            attachmentSize =
                attachmentContentProperties.getLong(ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENT_FILESIZE, -1);
            if (attachmentProperties.containsKey(ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENTTYPE)) {
                mediaType =
                    attachmentContentProperties.getString(ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENT_MEDIA_TYPE);
            }
        } else {
            attachmentSize = attachmentProperties.getLong(ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENT_SIZE, -1);
            if (attachmentProperties.containsKey(ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENTTYPE)) {
                mediaType = attachmentProperties.getString(ConfluenceXMLPackage.KEY_ATTACHMENT_CONTENTTYPE);
            }
        }

        Long version = this.confluencePackage.getAttachementVersion(attachmentProperties);

        long originalRevisionId =
            this.confluencePackage.getAttachmentOriginalVersionId(attachmentProperties, attachmentId);
        File contentFile;
        try {
            contentFile = this.confluencePackage.getAttachmentFile(pageId, originalRevisionId, version);
        } catch (Exception e) {
            this.logger.warn("Failed to find file corresponding to version [{}] attachment [{}] in page [{}]: {}",
                version, attachmentName, pageId, ExceptionUtils.getRootCauseMessage(e));

            return;
        }

        FilterEventParameters attachmentParameters = new FilterEventParameters();
        if (mediaType != null) {
            attachmentParameters.put(WikiAttachmentFilter.PARAMETER_CONTENT_TYPE, mediaType);
        }
        if (attachmentProperties.containsKey(ConfluenceXMLPackage.KEY_ATTACHMENT_CREATION_AUTHOR)) {
            attachmentParameters.put(WikiAttachmentFilter.PARAMETER_CREATION_AUTHOR,
                attachmentProperties.getString(ConfluenceXMLPackage.KEY_ATTACHMENT_CREATION_AUTHOR));
        }
        if (attachmentProperties.containsKey(ConfluenceXMLPackage.KEY_ATTACHMENT_CREATION_DATE)) {
            try {
                attachmentParameters.put(WikiAttachmentFilter.PARAMETER_CREATION_DATE, this.confluencePackage
                    .getDate(attachmentProperties, ConfluenceXMLPackage.KEY_ATTACHMENT_CREATION_DATE));
            } catch (Exception e) {
                if (this.properties.isVerbose()) {
                    this.logger.error("Failed to parse date", e);
                }
            }
        }

        attachmentParameters.put(WikiAttachmentFilter.PARAMETER_REVISION, String.valueOf(version));
        if (attachmentProperties.containsKey(ConfluenceXMLPackage.KEY_ATTACHMENT_REVISION_AUTHOR)) {
            attachmentParameters.put(WikiAttachmentFilter.PARAMETER_REVISION_AUTHOR,
                attachmentProperties.getString(ConfluenceXMLPackage.KEY_ATTACHMENT_REVISION_AUTHOR));
        }
        if (attachmentProperties.containsKey(ConfluenceXMLPackage.KEY_ATTACHMENT_REVISION_DATE)) {
            try {
                attachmentParameters.put(WikiAttachmentFilter.PARAMETER_REVISION_DATE, this.confluencePackage
                    .getDate(attachmentProperties, ConfluenceXMLPackage.KEY_ATTACHMENT_REVISION_DATE));
            } catch (Exception e) {
                if (this.properties.isVerbose()) {
                    this.logger.error("Failed to parse date", e);
                }
            }
        }
        if (attachmentProperties.containsKey(ConfluenceXMLPackage.KEY_ATTACHMENT_REVISION_COMMENT)) {
            attachmentParameters.put(WikiAttachmentFilter.PARAMETER_REVISION_COMMENT,
                attachmentProperties.getString(ConfluenceXMLPackage.KEY_ATTACHMENT_REVISION_COMMENT));
        }

        // WikiAttachment

        try (FileInputStream fis = new FileInputStream(contentFile)) {
            proxyFilter.onWikiAttachment(attachmentName, fis,
                attachmentSize != -1 ? attachmentSize : contentFile.length(), attachmentParameters);
        } catch (Exception e) {
            throw new FilterException("Failed to read attachment", e);
        }
    }

    private void readPageTags(ConfluenceProperties pageProperties, ConfluenceFilter proxyFilter,
        Map<String, ConfluenceProperties> pageTags) throws FilterException
    {
        FilterEventParameters pageTagsParameters = new FilterEventParameters();
        String objectName = getObjectName(pageProperties);

        // Tag object
        pageTagsParameters.put(WikiObjectFilter.PARAMETER_NUMBER, 0);
        pageTagsParameters.put(WikiObjectFilter.PARAMETER_CLASS_REFERENCE, "XWiki.TagClass");
        proxyFilter.beginWikiObject(objectName, pageTagsParameters);

        // get page tags separated by | as string
        StringBuilder tagBuilder = new StringBuilder();
        String prefix = "";
        for (String tag : pageTags.keySet()) {
            tagBuilder.append(prefix);
            tagBuilder.append(tag);
            prefix = "|";
        }

        // <tags> object property
        proxyFilter.onWikiObjectProperty("tags", tagBuilder.toString(), new FilterEventParameters());

        proxyFilter.endWikiObject(objectName, pageTagsParameters);
    }

    private void readPageComment(ConfluenceProperties pageProperties, ConfluenceFilter proxyFilter, Long commentId,
        Map<Long, ConfluenceProperties> pageComments, Map<Long, Integer> commentIndeces) throws FilterException
    {
        String objectName = getObjectName(pageProperties);
        FilterEventParameters commentParameters = new FilterEventParameters();

        // Comment object
        commentParameters.put(WikiObjectFilter.PARAMETER_NUMBER, commentIndeces.get(commentId));
        commentParameters.put(WikiObjectFilter.PARAMETER_CLASS_REFERENCE, "XWiki.XWikiComments");
        proxyFilter.beginWikiObject(objectName, commentParameters);

        // object properties
        ConfluenceProperties commentProperties = pageComments.get(commentId);

        // creator
        String commentCreator;
        if (commentProperties.containsKey("creatorName")) {
            // old creator reference by name
            commentCreator = commentProperties.getString("creatorName");
        } else {
            // new creator reference by key
            commentCreator = commentProperties.getString("creator");
            commentCreator = resolveUserName(commentCreator, commentCreator);
        }
        String commentCreatorReference = toUserReference(commentCreator);

        // content
        String commentBodyContent = this.confluencePackage.getCommentText(commentProperties, commentId);
        int commentBodyType = this.confluencePackage.getCommentBodyType(commentProperties, commentId);
        String commentText = commentBodyContent;
        if (commentBodyContent != null && this.properties.isConvertToXWiki()) {
            try {
                commentText = convertToXWiki21(commentBodyContent, commentBodyType);
            } catch (Exception e) {
                this.logger.error("Failed to convert content of the comment with id [{}]", commentId, e);
            }
        }

        // creation date
        Date commentDate = null;
        try {
            commentDate = this.confluencePackage.getDate(commentProperties, "creationDate");
        } catch (java.text.ParseException e) {
            if (this.properties.isVerbose()) {
                this.logger.error("Failed to parse date", e);
            }
        }

        // parent (replyto)
        Integer parentIndex = null;
        if (commentProperties.containsKey("parent")) {
            Long parentId = commentProperties.getLong("parent");
            parentIndex = commentIndeces.get(parentId);
        }

        proxyFilter.onWikiObjectProperty("author", commentCreatorReference, new FilterEventParameters());
        proxyFilter.onWikiObjectProperty("comment", commentText, new FilterEventParameters());
        proxyFilter.onWikiObjectProperty("date", commentDate, new FilterEventParameters());
        proxyFilter.onWikiObjectProperty("replyto", parentIndex, new FilterEventParameters());

        proxyFilter.endWikiObject(objectName, commentParameters);
    }

    private String getObjectName(ConfluenceProperties pageProperties)
    {
        // get parent name from reference
        String parentName = "";
        try {
            EntityReference parentReference =
                this.confluencePackage.getReferenceFromId(pageProperties, ConfluenceXMLPackage.KEY_PAGE_PARENT);
            if (parentReference != null) {
                parentName = parentReference.getName();
            }
        } catch (Exception e) {
            if (this.properties.isVerbose()) {
                this.logger.warn("Failed to parse parent", e);
            }
        }

        // use space name if there is no parent
        if (parentName.isEmpty()) {
            try {
                parentName = this.confluencePackage
                    .getSpaceName(Long.valueOf(pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_SPACE)));
            } catch (NumberFormatException | ConfigurationException e) {
                if (this.properties.isVerbose()) {
                    this.logger.warn("Failed to parse space", e);
                }
            }
        }

        // get page name
        String pageName = pageProperties.getString(ConfluenceXMLPackage.KEY_PAGE_TITLE);

        // create full page name from parent + title + WebHome
        StringBuilder nameBuilder = new StringBuilder();
        if (!parentName.isEmpty()) {
            nameBuilder.append(parentName);
            nameBuilder.append(".");
        }
        if (!pageName.isEmpty()) {
            nameBuilder.append(pageName);
            nameBuilder.append(".");
        }
        nameBuilder.append("WebHome");

        return nameBuilder.toString();
    }

    private ConfluenceProperties getContentProperties(ConfluenceProperties properties, String key)
        throws FilterException
    {
        try {
            return this.confluencePackage.getContentProperties(properties, key);
        } catch (Exception e) {
            throw new FilterException("Failed to parse content properties", e);
        }
    }
}
