/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
// SCP means ServiceConfigProperty

var App = require('app');

App.ServiceConfigView.SCPOverriddenRowsView = Ember.View.extend({
  templateName: require('templates/common/configs/overriddenProperty'),
  controllerBinding: 'App.router.mainServiceInfoConfigsController',
  serviceConfigProperty: null, // is passed dynamically at runtime where ever
  // we are declaring this from configs.hbs ( we are initializing this from UI )
  categoryConfigs: null, // just declared as viewClass need it
  
  showOverrideWindow: function (event) {
    // App.ServiceConfigsByCategoryView in which the current view is nested
    this.get('parentView').showOverrideWindow(event);
  },

  removeOverride: function (event) {
    // arg 1 SCP means ServiceConfigProperty
    var scpToBeRemoved = event.contexts[0];
    var overrides = this.get('serviceConfigProperty.overrides');
    overrides = overrides.without(scpToBeRemoved);
    this.set('serviceConfigProperty.overrides', overrides);
  },
  
  hostsCountView: Em.View.extend({
    classNames: ['overridden-hosts-view'],
    template: Ember.Handlebars.compile("<a class=\"action overriden-hosts-link\" href=\"#\" {{action showOverrideWindow overriddenSCP controller target=\"view.parentView\" }} rel=\"tooltip\" {{bindAttr data-original-title=\"view.hostsList\"}} >{{view.overriddenSCP.selectedHostOptions.length}} hosts </a>"),
    overriddenSCP: null,
    didInsertElement: function () {
      var links = $(".overriden-hosts-link");
      console.log(links);
      links.tooltip({html:true, placement:"right"});
    },
    /**
     * New line separated list of hosts
     * @type String
     */
    hostsList: function () {
      var tooltip = "<ul>";
      var hosts = this.get('overriddenSCP.selectedHostOptions');
      if (hosts != null) {
        hosts.forEach(function (host) {
          var hostObj = App.Host.find(host);
          if (hostObj != null) {
            host = hostObj.get('publicHostName');
          }
          tooltip += ("<li>" + host + "</li>");
        });
      }
      tooltip += "</ul>";
      return tooltip;
    }.property('overriddenSCP', 'overriddenSCP.selectedHostOptions')
  })
});
