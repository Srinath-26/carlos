/**
 * Copyright (c) 2001-2002. Department of Family Medicine, McMaster University. All Rights Reserved.
 * This software is published under the GPL GNU General Public License.
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 * <p>
 * This software was written for the
 * Department of Family Medicine
 * McMaster University
 * Hamilton
 * Ontario, Canada

 * <p>
 * Now maintained by the CARLOS EMR Project (2026+).
 * https://github.com/carlos-emr/carlos
 * CARLOS has no affiliation with OSCAR or McMaster University.
 */


package io.github.carlos_emr.carlos.messenger.pageUtil;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.github.carlos_emr.carlos.managers.SecurityInfoManager;
import io.github.carlos_emr.carlos.utility.LoggedInInfo;
import io.github.carlos_emr.carlos.utility.MiscUtils;
import io.github.carlos_emr.carlos.utility.SpringUtils;

import io.github.carlos_emr.carlos.util.Doc2PDF;

import org.apache.commons.codec.binary.Base64;
import org.apache.struts2.ActionSupport;
import org.apache.struts2.ServletActionContext;
import org.apache.struts2.interceptor.parameter.StrutsParameter;

/**
 * Struts2 action for viewing PDF attachments stored in XML format within messages.
 *
 * <p>This action retrieves and displays PDF attachments that have been stored in an
 * XML structure within the session. It handles PDF files that were attached to messages
 * using the XML-based attachment system where multiple PDF files can be embedded within
 * a single XML document structure with CONTENT tags.</p>
 *
 * <p>Key functionality:</p>
 * <ul>
 *   <li>Validates read permissions for messaging</li>
 *   <li>Retrieves PDF attachment XML from session</li>
 *   <li>Extracts specific PDF by file ID from XML structure</li>
 *   <li>Streams PDF content directly to browser</li>
 * </ul>
 *
 * <p>The PDF attachment storage format:</p>
 * <ul>
 *   <li>PDFs are stored as Base64-encoded strings within XML</li>
 *   <li>Multiple PDFs can exist within one XML document</li>
 *   <li>Each PDF is wrapped in a CONTENT tag</li>
 *   <li>Files are accessed by their index (file_id)</li>
 * </ul>
 *
 * <p>Error handling:</p>
 * <ul>
 *   <li>Returns NONE after streaming PDF content directly to the response</li>
 *   <li>Rejects a missing, non-numeric or out-of-range file_id with HTTP 400</li>
 *   <li>Answers unreadable attachment XML, or an attachment that is not a PDF, with HTTP 500</li>
 * </ul>
 *
 * @version 2.0
 * @since 2003
 * @see Doc2PDF
 * @see MsgViewPDFAttachment2Action
 * @see MsgAttachPDF2Action
 */
public class MsgViewPDF2Action extends ActionSupport {
    /**
     * Error message sent with HTTP 400 when file_id is missing, not a number, or out of range.
     */
    private static final String INVALID_FILE_ID = "Invalid or out-of-range file_id";

    /**
     * Bytes every PDF starts with; checked before anything is written to the response.
     */
    private static final byte[] PDF_HEADER = new byte[] {'%', 'P', 'D', 'F', '-'};

    /**
     * HTTP request object for accessing session data.
     */
    HttpServletRequest request = ServletActionContext.getRequest();

    /**
     * HTTP response object for streaming PDF content to browser.
     */
    HttpServletResponse response = ServletActionContext.getResponse();

    /**
     * Security manager for enforcing read permissions on messaging operations.
     */
    private SecurityInfoManager securityInfoManager = SpringUtils.getBean(SecurityInfoManager.class);

    /**
     * Executes the PDF viewing workflow.
     *
     * <p>This method performs the following operations:</p>
     * <ol>
     *   <li>Validates that the user has read permissions for messaging</li>
     *   <li>Retrieves the PDF attachment XML from the session</li>
     *   <li>Parses the XML to extract CONTENT tags containing PDFs</li>
     *   <li>Retrieves the specific PDF by its index (file_id)</li>
     *   <li>Streams the PDF binary content to the browser</li>
     * </ol>
     *
     * <p>The method expects the PDF attachment data to be stored in the session
     * under the key "PDFAttachment" as an XML string. The file_id parameter
     * indicates which PDF to extract from the XML (0-based index).</p>
     *
     * <p>Every path that touches the response owns it and returns {@link #NONE}:
     * a missing, non-numeric or out-of-range file_id gets HTTP 400, attachment XML
     * that cannot be read gets HTTP 500, an attachment that does not decode to a PDF
     * (one that failed to render when it was attached) gets HTTP 500, and a PDF that
     * cannot be written gets the HTTP 500 that {@link Doc2PDF#PrintPDFFromBytes} sends.
     * Only a session with no PDF attachment, where nothing has been written, falls
     * back to the view result.</p>
     *
     * @return {@link #NONE} after streaming the PDF or sending an error response;
     *         {@link #SUCCESS} only when the session holds no PDF attachment
     * @throws IOException if there's an error writing to response stream
     * @throws ServletException if there's a servlet processing error
     * @throws SecurityException if user lacks read permissions for messaging
     */
    public String execute() throws IOException, ServletException {
        // Verify user has read permission for messages
        if (!securityInfoManager.hasPrivilege(LoggedInInfo.getLoggedInInfoFromSession(request), "_msg", "r", null)) {
            throw new SecurityException("missing required sec object (_msg)");
        }

        // Retrieve PDF attachment XML from session
        String pdfAttachment = (String) request.getSession().getAttribute("PDFAttachment");
        if (pdfAttachment == null || pdfAttachment.isEmpty()) {
            // Nothing has been written to the response, so the view result can still render
            return SUCCESS;
        }

        int fileID;
        try {
            fileID = Integer.parseInt(this.getFile_id());
        } catch (NumberFormatException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, INVALID_FILE_ID);
            return NONE;
        }

        // Extract all CONTENT tags from XML
        List<?> attachments;
        try {
            attachments = Doc2PDF.getXMLTagValue(pdfAttachment, "CONTENT");
        } catch (Exception e) {
            // The session holds attachment XML this action cannot read: a server-side fault
            MiscUtils.getLogger().error("Could not read the PDF attachments held in the session", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not read the PDF attachment");
            return NONE;
        }

        // Reject invalid file_id values before accessing the attachment list
        if (fileID < 0 || fileID >= attachments.size()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, INVALID_FILE_ID);
            return NONE;
        }

        // Base64 decoding is lenient: an attachment that failed to render is stored with
        // "null" as its content, which decodes to a few bytes that are not a PDF
        byte[] pdf = Base64.decodeBase64((String) attachments.get(fileID));
        if (!isPdf(pdf)) {
            MiscUtils.getLogger().warn("PDF attachment {} held in the session is not a PDF", fileID);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not read the PDF attachment");
            return NONE;
        }

        // Stream PDF to browser; Doc2PDF sends its own error response if it cannot
        Doc2PDF.PrintPDFFromBytes(response, pdf);
        return NONE;
    }

    /**
     * Checks that decoded attachment bytes start with the PDF header.
     *
     * @param bytes byte[] the decoded attachment
     * @return true if the bytes start with {@code %PDF-}
     */
    private static boolean isPdf(byte[] bytes) {
        return bytes.length >= PDF_HEADER.length
                && Arrays.equals(bytes, 0, PDF_HEADER.length, PDF_HEADER, 0, PDF_HEADER.length);
    }

    /**
     * Attachment parameter, currently not used in implementation.
     */
    String attachment = null;

    /**
     * Index of the PDF file to retrieve from the XML structure.
     */
    String file_id = null;

    /**
     * Sets the attachment parameter.
     *
     * <p>Note: This parameter is not currently used in the execute method.
     * The actual attachment is retrieved from the session.</p>
     *
     * @param attachment String the attachment parameter
     */
    @StrutsParameter
    public void setAttachment(String attachment) {
        this.attachment = attachment;
    }

    /**
     * Gets the attachment parameter.
     *
     * @return String the attachment parameter
     */
    public String getAttachment() {
        return attachment;
    }

    /**
     * Sets the file ID index for PDF retrieval.
     *
     * @param file_id String the 0-based index of the PDF to retrieve
     */
    @StrutsParameter
    public void setFile_id(String file_id) {
        this.file_id = file_id;
    }

    /**
     * Gets the file ID index.
     *
     * @return String the file ID index
     */
    public String getFile_id() {
        return file_id;
    }
}
