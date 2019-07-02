<%--

    Copyright 2008 The University of North Carolina at Chapel Hill

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

            http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

--%>
<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ page trimDirectiveWhitespaces="true" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib prefix="cdr" uri="http://cdr.lib.unc.edu/cdrUI"%>

<c:choose>
    <c:when test="${not empty briefObject.countMap}">
        <c:set var="childCount" value="${briefObject.countMap.child}"/>
    </c:when>
    <c:otherwise>
        <c:set var="childCount" value="0"/>
    </c:otherwise>
</c:choose>

<c:choose>
    <c:when test="${childCount == 1}">
        <c:set var="pluralCount" value="item"/>
    </c:when>
    <c:otherwise>
        <c:set var="pluralCount" value="items"/>
    </c:otherwise>
</c:choose>

<c:url var="fullRecordUrl" scope="page" value="record/${briefObject.id}" />
<div class="contentarea">
    <div class="columns browse-header">
        <div class="column is-12">
            <h2><c:out value="${briefObject.title}" /> <span class="item-count">(<c:out value="${childCount}" /> items)</span></h2>
            <p><strong>Subjects:</strong>
                <c:choose>
                    <c:when test="${not empty briefObject.subject}">
                        <c:forEach var="subject" items="${briefObject.subject}">
                            <c:out value="${subject}"/>
                        </c:forEach>
                    </c:when>
                    <c:otherwise>
                        There are no subjects listed for this record
                    </c:otherwise>
                </c:choose>
            </p>
            <div class="column is-3">
                <c:set var="thumbnailObject" value="${briefObject}" scope="request" />
                <c:import url="common/thumbnail.jsp">
                    <c:param name="target" value="file" />
                    <c:param name="size" value="large" />
                </c:import>
            </div>
            <div class="column is-9">
                <c:choose>
                    <c:when test="${not empty briefObject.abstractText}">
                        <c:set var="truncatedAbstract" value="${cdr:truncateText(briefObject.abstractText, 250)}"/>
                        <c:choose>
                            <c:when test="${fn:length(briefObject.abstractText) > 250}">
                                <p id="truncated-abstract"><c:out value="${truncatedAbstract}" />...</p>
                                <p id="full-abstract" class="hidden"><c:out value="${briefObject.abstractText}" /></p>
                                <p><a id="show-abstract" href="#">Read more</a></p>
                            </c:when>
                            <c:when test="${fn:length(briefObject.abstractText) > 250}">
                                <p><c:out value="${briefObject.abstractText}" /></p>
                            </c:when>
                            <c:otherwise>
                                <p><c:out value="${briefObject.abstractText}" /></p>
                            </c:otherwise>
                        </c:choose>
                    </c:when>
                    <c:otherwise>
                        <p>There is no description available for this record.</p>
                    </c:otherwise>
                </c:choose>
            </div>
        </div>
    </div>
    <script>
        (function() {
            var clicked = document.getElementById('show-abstract');
            var partial_abstract = document.getElementById('truncated-abstract').classList;
            var full_abstract = document.getElementById('full-abstract').classList;

            clicked.addEventListener('click', function (e) {
                e.preventDefault();
                partial_abstract.toggle('hidden');
                full_abstract.toggle('hidden');
                this.innerText = partial_abstract.contains('hidden') ? 'Read less' : 'Read more';
            });
        })();
    </script>
    <c:import url="fullRecord/browseView.jsp" />
</div>