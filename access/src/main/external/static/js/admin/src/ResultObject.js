/*

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

 */
define('ResultObject', [ 'jquery', 'jquery-ui', 'RemoteStateChangeMonitor', 'DeleteObjectButton',
		'PublishObjectButton', 'EditAccessControlForm', 'ModalLoadingOverlay'], function($, ui, RemoteStateChangeMonitor) {
	var defaultOptions = {
			animateSpeed : 100,
			metadata : null,
			selected : false,
			selectable : true,
			selectCheckboxInitialState : false
		};
	
	function ResultObject(element, options) {
		this.init(element, options);
	};
	
	ResultObject.prototype.init = function(element, options) {
		this.element = element;
		this.element.data('resultObject', this);
		this.options = $.extend({}, defaultOptions, options);
		this.metadata = this.options.metadata;
		this.links = [];
		this.pid = this.options.id;
		this.overlayInitialized = false;
		if (this.options.selected)
			this.select();
	};
	
	ResultObject.prototype.activateActionMenu = function() {
		var $menuIcon = $(".menu_box img", this.element);
		if (!this.actionMenuInitialized) {
			this.initializeActionMenu();
			$menuIcon.click();
			return;
		}
		if (this.actionMenu.children().length == 0)
			return;
		$menuIcon.parent().css("background-color", "#7BAABF");
		return;
	};
	
	ResultObject.prototype.initializeActionMenu = function() {
		var self = this;
		
		this.actionMenuInitialized = true;
		
		this.actionMenu = $(".menu_box ul", this.element);
		if (this.actionMenu.children().length == 0)
			return;
		
		var menuIcon = $(".menu_box img", this.element);
		
		// Set up the dropdown menu
		menuIcon.qtip({
			content: self.actionMenu,
			position: {
				at: "bottom right",
				my: "top right"
			},
			style: {
				classes: 'qtip-light',
				tip: false
			},
			show: {
				event: 'click',
				delay: 0
			},
			hide: {
				delay: 2000,
				event: 'unfocus mouseleave click',
				fixed: true, // Make sure we can interact with the qTip by setting it as fixed
				effect: function(offset) {
					menuIcon.parent().css("background-color", "transparent");
					$(this).fadeOut(100);
				}
			},
			events: {
				render: function(event, api) {
					self.initializePublishLinks($(this));
					self.initializeDeleteLinks($(this));
				}
			}
		});
		
		self.actionMenu.children().click(function(){
			menuIcon.qtip('hide');
		});
		
		this.actionMenu.children(".edit_access").click(function(){
			menuIcon.qtip('hide');
			self.highlight();
			var dialog = $("<div class='containingDialog'><img src='/static/images/admin/loading-large.gif'/></div>");
			dialog.dialog({
				autoOpen: true,
				width: 500,
				height: 'auto',
				maxHeight: 800,
				minWidth: 500,
				modal: true,
				title: 'Access Control Settings',
				close: function() {
					dialog.remove();
					self.unhighlight();
				}
			});
			dialog.load("acl/" + self.pid, function(responseText, textStatus, xmlHttpRequest){
				dialog.dialog('option', 'position', 'center');
			});
		});
	};
	
	ResultObject.prototype._destroy = function () {
		if (this.overlayInitialized) {
			this.element.modalLoadingOverlay('close');
		}
	};

	ResultObject.prototype.initializePublishLinks = function(baseElement) {
		var links = baseElement.find(".publish_link");
		if (!links)
			return;
		this.links['publish'] = links;
		var obj = this;
		$(links).publishObjectButton({
			pid : obj.pid,
			parentObject : obj,
			defaultPublish : $.inArray("Unpublished", this.metadata.status) == -1
		});
	};

	ResultObject.prototype.initializeDeleteLinks = function(baseElement) {
		var links = baseElement.find(".delete_link");
		if (!links)
			return;
		this.links['delete'] = links;
		var obj = this;
		$(links).deleteObjectButton({
			pid : obj.pid,
			parentObject : obj
		});
	};

	ResultObject.prototype.disable = function() {
		this.options.disabled = true;
		this.element.css("cursor", "default");
		this.element.find(".ajaxCallbackButton").each(function(){
			$(this)[$(this).data("callbackButtonClass")].call($(this), "disable");
		});
	};

	ResultObject.prototype.enable = function() {
		this.options.disabled = false;
		this.element.css("cursor", "pointer");
		this.element.find(".ajaxCallbackButton").each(function(){
			$(this)[$(this).data("callbackButtonClass")].call($(this), "enable");
		});
	};
	
	ResultObject.prototype.isEnabled = function() {
		return !this.options.disabled;
	};

	ResultObject.prototype.toggleSelect = function() {
		if (this.element.hasClass("selected")) {
			this.unselect();
		} else {
			this.select();
		}
	};
	
	ResultObject.prototype.getElement = function () {
		return this.element;
	};
	
	ResultObject.prototype.getPid = function () {
		return this.pid;
	};
	
	ResultObject.prototype.getMetadata = function () {
		return this.metadata;
	};

	ResultObject.prototype.select = function() {
		if (!this.options.selectable)
			return;
		this.element.addClass("selected");
		if (!this.checkbox)
			this.checkbox = this.element.find("input[type='checkbox']");
		this.checkbox.prop("checked", true);
	};

	ResultObject.prototype.unselect = function() {
		if (!this.options.selectable)
			return;
		this.element.removeClass("selected");
		if (!this.checkbox)
			this.checkbox = this.element.find("input[type='checkbox']");
		this.checkbox.prop("checked", false);
	};
	
	ResultObject.prototype.highlight = function() {
		this.element.addClass("highlighted");
	};
	
	ResultObject.prototype.unhighlight = function() {
		this.element.removeClass("highlighted");
	};

	ResultObject.prototype.isSelected = function() {
		return this.element.hasClass("selected");
	};

	ResultObject.prototype.setState = function(state) {
		if ("idle" == state || "failed" == state) {
			this.enable();
			this.element.removeClass("followup working").addClass("idle");
			this.updateOverlay('hide');
		} else if ("working" == state) {
			this.updateOverlay('show');
			this.disable();
			this.element.switchClass("idle followup", "working", this.options.animateSpeed);
		} else if ("followup" == state) {
			this.element.removeClass("idle").addClass("followup", this.options.animateSpeed);
		}
	};

	ResultObject.prototype.getActionLinks = function(linkNames) {
		return this.links[linkNames];
	};
	
	ResultObject.prototype.isPublished = function() {
		if (!$.isArray(this.metadata.status)){
			return true;
		}
		return $.inArray("Unpublished", this.metadata.status) == -1;
	};
	
	ResultObject.prototype.publish = function() {
		var links = this.links['publish'];
		if (links.length == 0)
			return;
		$(links[0]).publishObjectButton('activate');
	};
	
	ResultObject.prototype['delete'] = function() {
		var links = this.links['delete'];
		if (links.length == 0)
			return;
		$(links[0]).deleteObjectButton('activate');
	};

	ResultObject.prototype.deleteElement = function() {
		var obj = this;
		obj.element.hide(obj.options.animateSpeed, function() {
			obj.element.remove();
			if (obj.options.resultObjectList) {
				obj.options.resultObjectList.removeResultObject(obj.pid);
			}
		});
	};
	
	ResultObject.prototype.updateVersion = function(newVersion) {
		if (newVersion != this.metadata._version_) {
			this.metadata._version_ = newVersion;
			return true;
		}
		return false;
	};
	
	ResultObject.prototype.setStatusText = function(text) {
		this.updateOverlay('setText', [text]);
	};
	
	ResultObject.prototype.updateOverlay = function(fnName, fnArgs) {
		// Check to see if overlay is initialized
		if (!this.overlayInitialized) {
			this.overlayInitialized = true;
			this.element.modalLoadingOverlay({'text' : 'Working...', 'autoOpen' : false});
		}
		var overlay = this.element.data("modalLoadingOverlay");
		overlay[fnName].apply(overlay, fnArgs);
	};
	
	ResultObject.prototype.refresh = function(immediately) {
		this.updateOverlay('show');
		this.setStatusText('Refreshing...');
		if (immediately) {
			this.options.resultObjectList.refreshObject(this.pid);
			return;
		}
		var self = this;
		var followupMonitor = new RemoteStateChangeMonitor({
			'checkStatus' : function(data) {
				return (data != self.metadata._version_);
			},
			'checkStatusTarget' : this,
			'statusChanged' : function(data) {
				self.options.resultObjectList.refreshObject(self.pid);
			},
			'statusChangedTarget' : this, 
			'checkStatusAjax' : {
				url : "services/rest/item/" + self.pid + "/solrRecord/version",
				dataType : 'json'
			}
		});
		
		followupMonitor.performPing();
	};
	
	return ResultObject;
});