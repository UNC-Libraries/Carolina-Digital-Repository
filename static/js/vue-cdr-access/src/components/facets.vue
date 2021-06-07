<template>
    <div id="facetList" class="contentarea">
        <h2 class="facet-header">Filter results by...</h2>
        <div class="selected_facets" v-if="updateFacetInfo.length > 0">
            <ul>
                <li class="selected-facet-entry" v-for="entry in updateFacetInfo">
                    <div @click="updateAll(entry, true)"><i class="fas fa-times"></i> {{ displayText(entry.value) }}</div>
                </li>
            </ul>
        </div>
        <div class="facet-display" v-if="facet.values.length" v-for="facet in this.facetsToSelect">
            <div v-if="showFacetDisplay(facet)">
                <h3>{{ facetName(facet.name) }}</h3>
                <ul>
                    <li v-for="value in facet.values">
                        <a @click.prevent="updateAll(value)">{{ value.displayValue }} ({{ value.count }})</a>
                    </li>
                </ul>
            </div>
        </div>
    </div>
</template>

<script>
    import clonedeep from 'lodash.clonedeep';
    import routeUtils from '../mixins/routeUtils';

    const UUID_REGEX = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
    const POSSIBLE_FACET_PARAMS = ['collection', 'format', 'language', 'subject'];

    export default {
        name: 'facets',

        props: {
            facetList: Array
        },

        mixins: [routeUtils],

        data() {
            return {
                selected_facets: [],
                facet_display: []
            }
        },

        watch: {
            selected_facets() {
                this.selectedFacets();
            },

           '$route.query'(route) {
               this.selected_facets = [];
               this.setFacetsFromParams();
            }
        },

        computed: {
            routeHasCollectionId() {
                return UUID_REGEX.test(this.$route.path);
            },

            updateFacetInfo() {
                const display_list = [];
                this.selected_facets.map((f) => {
                    const regex = /^.*?=/;
                    const facet_type = f.match(regex);
                    const facets = f.split('||');
                    facets.forEach((fv) => {
                        display_list.push({
                            type: facet_type[0].replace('=', ''),
                            value: fv.toLowerCase().replace(regex, '')
                        });
                    });
                });
                return display_list;
            },

            facetsToSelect() {
                if (this.selected_facets.length === 0) {
                    return this.facetList;
                }

                const selectable_facets = [];
                this.facetList.forEach((f) => {
                    const cloned_facet_object = clonedeep(f);
                    cloned_facet_object.values = cloned_facet_object.values.filter(v => {
                        const has_index = this.facet_display.findIndex((hv) => {
                            return hv.facet_value.toLowerCase() === v.displayValue.toLowerCase();
                        });
                        return has_index === -1;
                    });
                    selectable_facets.push(cloned_facet_object);
                });

                return selectable_facets;
            },

            allFacetValues() {
                return this.facetList.map(f => f.values).flat();
            }
        },

        methods: {
            updateAll(facet, remove = false) {
                if (remove) {
                    this.facetInfoRemove(facet);
                } else {
                    this.updateSelectedFacet(facet);
                }
            },

            updateSelectedFacet(facet) {
                const facet_value = this.facetValue(facet);
                const facet_type = facet_value.split('=');
                const found_facet = this.selected_facets.findIndex((f)=> {
                    const regx = new RegExp(facet_type[0]);
                    return regx.test(f);
                });

                // Remove old facet value, instead of replacing or selected_facet watcher doesn't fire
                if (found_facet !== -1) {
                    this.selected_facets.splice(found_facet, 1, facet_value);
                } else {
                    this.selected_facets.push(facet_value);
                }
            },

            /**
             * Determine display text for selected facets
             * @param value
             * @returns {string|*}
             */
            displayText(value) {
                const display = this.facet_display.find(fv => fv.facet === value);
                return display.facet_value;
            },

            /**
             * Used to determine if a collection has been selected. If so, remove collections from facets to select from
             * @param facet
             * @returns {boolean|boolean}
             */
            showFacetDisplay(facet) {
                return facet.name !== 'PARENT_COLLECTION' || (facet.name === 'PARENT_COLLECTION' && !this.routeHasCollectionId);
            },

            /**
             * Push new url after a facet is selected/deselected
             */
            selectedFacets() {
                let updated_facet_params = this.updateUrl();
                let base_search = {
                    query: this.urlParams({}, true)
                };

                // Unset current facets
                POSSIBLE_FACET_PARAMS.forEach((facet) => delete base_search.query[facet]);
                // Add/Update with new facets
                base_search.query = Object.assign(base_search.query, updated_facet_params.queryFacets);

                this.$router.push(base_search).catch((e) => {
                    if (this.nonDuplicateNavigationError(e)) {
                        throw e;
                    }
                });
            },

            /**
             * Determine parameters to build the new url after a facet is selected/deselected
             * @returns {{}}
             */
            updateUrl() {
                return {
                    path: '/search/',
                    queryFacets: this._formatFacets(this.selected_facets)
                };
            },

           /**
             * Remove full facet info for deselected facets and their childrent
             * @param facet
             */
            facetInfoRemove(facet) {
               const type_regex = new RegExp(facet.type);
               const current_index = this.selected_facets.findIndex(sf => type_regex.test(sf));

               if (current_index !== -1) {
                   const facet_parts = this.selected_facets[current_index].split('=');
                   const current_values = facet_parts[1].split('||');
                   const current_value_regex = new RegExp(facet.value, 'i');
                   const updated_values = current_values.filter(f => !current_value_regex.test(f)).join('||');

                   if (updated_values === '') {
                       this.selected_facets.splice(current_index, 1);
                   } else {
                       this.selected_facets.splice(current_index, 1, `${facet_parts[0]}=${updated_values}`);
                   }

                   this.facet_display = this.facet_display.filter(dv => !current_value_regex.test(dv.facet));
               }
            },

            /**
             * Format facets for url
             * Avoid double encoding by decoding first
             * @param updated_facets
             * @returns {{}}
             * @private
             */
            _formatFacets(updated_facets) {
                let formatted_facets = {};
                updated_facets.forEach((facet) => {
                    let facet_pieces = facet.split('=');
                    formatted_facets[facet_pieces[0]] = encodeURIComponent(decodeURIComponent(facet_pieces[1]));
                });

                return formatted_facets;
            },

            /**
             * Determine facet header text
             * @param value
             * @returns {string|*}
             */
            facetName(value) {
                if (value === 'PARENT_COLLECTION') {
                    return 'Collection'
                } else if (value === 'CONTENT_TYPE') {
                    return 'Format';
                } else if (value === 'LANGUAGE') {
                    return 'Language';
                } else if (value === 'SUBJECT') {
                    return 'Subject';
                } else {
                    return value;
                }
            },

            /**
             * Create base facet value for a selected facet
             * @param value
             * @returns {string}
             */
            facetValue(value) {
                let facet_type;

                if (value.fieldName === 'PARENT_COLLECTION') {
                    facet_type = 'collection='
                } else if (value.fieldName === 'CONTENT_TYPE') {
                    facet_type = 'format=';
                } else if (value.fieldName === 'LANGUAGE') {
                    facet_type = 'language=';
                } else if (value.fieldName === 'SUBJECT') {
                    facet_type = 'subject=';
                } else {
                    facet_type = '';
                }

                const current_facet_value = this.selected_facets.filter((f) => {
                    const regex = new RegExp(facet_type);
                    return regex.test(f);
                });

                if (current_facet_value.length === 1) {
                    const joined_facets = `${current_facet_value[0]}||${value.limitToValue}`;
                    const deduped_facets = Array.from(new Set(joined_facets.split('||')));
                    return `${deduped_facets.join('||')}`
                }

                return `${facet_type}${value.limitToValue}`;
            },

            /**
             * Determine if a facet value is in the url query and add it to selected facets, if so.
             * This method triggers every time a facet is added/removed from the mounted() method
             * as the url change triggers a reload.
             * @param type
             * @param facet_value
             * @private
             */
            _setFacetFromRoute(type, facet_value) {
                if (facet_value !== undefined) {
                    // Set values for url
                    const decoded_facet_value = decodeURIComponent(facet_value);
                    const updated_value = `${type}=${decoded_facet_value}`
                    this.selected_facets.push(updated_value);

                    // Set selected facets display text
                    decoded_facet_value.split('||').forEach((fv) => {
                        if (this.facet_display.findIndex(af => af.facet === fv) === -1) {
                            const facet_text = this.allFacetValues.find(dv => dv.limitToValue === fv);
                            this.facet_display.push({ facet_value: facet_text.displayValue, facet: fv });
                        }
                    });
                }
            },

            /**
             * Set all facets from current url
             */
            setFacetsFromParams() {
                let params = this.urlParams();
                POSSIBLE_FACET_PARAMS.forEach((type) => {
                    this._setFacetFromRoute(type, params[type]);
                });
            }
        },

        mounted() {
            this.setFacetsFromParams();
        }
    }
</script>

<style scoped lang="scss">
    #facetList {
        h3 {
            font-size: 18px;
            margin-bottom: 5px;
        }

        li {
            margin-left: 15px;
            padding-top: 5px;
        }

        .facet-display {
            margin-bottom: 25px;

            a {
                padding-left: 15px;
            }
        }

        .selected_facets {
            margin-bottom: 50px;
            margin-top: -20px;
        }

        .selected-facet-entry {
            text-indent: 0;

            div {
                text-transform: capitalize;
            }

            &:hover {
                cursor: pointer;
            }

            i {
                color: red;
                position: relative;
                vertical-align: text-top;
            }
        }
    }
</style>