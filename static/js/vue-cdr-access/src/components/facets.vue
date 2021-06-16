<template>
    <div id="facetList" class="contentarea">
        <h2 class="facet-header">Filter results by...</h2>
        <div class="selected_facets" v-if="facet_info.length > 0">
            <ul>
                <li class="selected-facet-entry" v-for="value in parsedFacetInfo">
                    <div @click="updateAll(value, true)"><i class="fas fa-times"></i> {{ value.displayValue }}</div>
                </li>
            </ul>
        </div>
        <div class="facet-display" v-if="facet.values.length" v-for="facet in this.facetList">
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
    import routeUtils from '../mixins/routeUtils';

    const UUID_REGEX = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
    const POSSIBLE_FACET_PARAMS = ['format', 'language', 'subject'];

    export default {
        name: 'facets',

        props: {
            facetList: Array
        },

        mixins: [routeUtils],

        data() {
            return {
                facet_info: [],
                selected_facets: []
            }
        },

        watch: {
            selected_facets() {
                this.selectedFacets();
            },

           '$route.query'(route) {
               this.facet_info = [];
               this.selected_facets = [];
               this.setFacetsFromParams();
            }
        },

        computed: {
            routeHasCollectionId() {
                return UUID_REGEX.test(this.$route.path);
            },

            parsedFacetInfo() {
                return this.facet_info.map((facet) => JSON.parse(facet));
            }
        },

        methods: {
            updateAll(facet, remove = false) {
                let facet_string = JSON.stringify(facet);

                if (remove) {
                    this.facetInfoRemove(facet);
                } else {
                    let facet_value = this.facetValue(facet);
                    this.facet_info.push(facet_string);
                    this.selected_facets.push(facet_value)
                }
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

                if (updated_facet_params.collection !== '') {
                    base_search.path = updated_facet_params.path;
                    base_search.query.collection_name = updated_facet_params.collection_name;
                } else {
                    base_search.name = 'searchRecords';
                    delete base_search.query.collection_name;
                }

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
             * @returns {{queryFacets: {}, path: string, collection: string}}
             */
            updateUrl() {
                let updated_facets = this.selected_facets;

                // Add/remove collection
                let path = '/search/';
                let collection_id = '';
                let collection_name = '';
                let collection = updated_facets.findIndex((facet) => {
                    return UUID_REGEX.test(facet);
                });

                if (collection !== -1) {
                    if (this.$route.query.collection_name !== undefined) {
                        collection_name = this.$route.query.collection_name;
                    } else {
                        let current_collection = this.parsedFacetInfo.find((f) => f.fieldName === 'ANCESTOR_PATH');
                        if (current_collection !== undefined) {
                            collection_name = current_collection.displayValue;
                        }
                    }

                    collection_id = this.selected_facets[collection];
                    path += collection_id;

                    // Remove collection from facets array without removing it from this.selected_facets
                    updated_facets = updated_facets.filter((f) => {
                        return !UUID_REGEX.test(f);
                    });
                }

                return {
                    collection: collection_id,
                    collection_name: collection_name,
                    path: path,
                    queryFacets: this._formatFacets(updated_facets)
                };
            },

           /**
             * Remove full facet info for deselected facets
             * @param facet
             */
            facetInfoRemove(facet) {
                this._updateSelectedFacets(facet);
                this._removeFacetInfo(facet);
            },

            /**
             * Remove children of base facets
             * @param facet
             * @private
             */
            _removeFacetInfo(facet) {
                let regex = new RegExp(facet.limitToValue);
                this.facet_info = this.facet_info.filter((f) => {
                    return !regex.test(JSON.parse(f).limitToValue)
                });
            },

            /**
             * Add/remove item from selected facet list
             * @param facet
             * @private
             */
            _updateSelectedFacets(facet) {
                let regex = new RegExp(this.facetValue(facet));
                this.selected_facets = this.selected_facets.filter((sf) => {
                    return !regex.test(sf);
                });
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

                if (value.fieldName === 'CONTENT_TYPE') {
                    facet_type = 'format=';
                } else if (value.fieldName === 'LANGUAGE') {
                    facet_type = 'language=';
                } else if (value.fieldName === 'SUBJECT') {
                    facet_type = 'subject=';
                } else {
                    facet_type = '';
                }

                return `${facet_type}${value.limitToValue}`;
            },

            /**
             * Determine if a collection id is in the url and add it to selected facets, if so
             * @private
             */
            _setCollectionFromRoute() {
                let collection = this.$route.path.match(UUID_REGEX);
                if (collection !== null) {
                    this.selected_facets.push(collection[0]);

                    let facet = {
                        displayValue: this.$route.query.collection_name,
                        limitToValue: collection[0],
                        value: collection[0],
                        fieldName: 'ANCESTOR_PATH'
                    };

                    this.facet_info.push(JSON.stringify(facet));
                }
            },

            /**
             * Determine if a facet value is in the url query and add it to selected facets, if so
             * @param type
             * @param facet_value
             * @private
             */
            _setFacetFromRoute(type, facet_value) {
                if (facet_value !== undefined) {
                    let decoded_facet_value = decodeURIComponent(facet_value);
                    let grouped_facet = decoded_facet_value.split('/');

                    grouped_facet.forEach((f, index) => {
                        let limit_value = decoded_facet_value;
                        let facet_string = f;
                        let value = `/${grouped_facet.join('^')},${f}`;

                        if (index === 0) {
                            limit_value = f;
                            facet_string = this._formatFacetValue(f);
                            value = `^${f},${facet_string}`;
                        }

                        let facet_value = `${type}=${limit_value}`;

                        if (this.selected_facets.findIndex((sf) => sf === facet_value) === -1) {
                            let facet = { displayValue: facet_string, limitToValue: limit_value, value: value };
                            facet.fieldName = (/format/.test(type)) ? 'CONTENT_TYPE' : type.toUpperCase();

                            this.selected_facets.push(facet_value);
                            this.facet_info.push(JSON.stringify(facet));
                        }
                    });
                }
            },

            /**
             * Format display text for facets pulled from url
             * @param facet
             * @returns {string}
             * @private
             */
            _formatFacetValue(facet) {
                let first_letter = facet.substr(0, 1).toUpperCase();
                return `${first_letter}${facet.substr(1)}`
            },

            /**
             * Set all facets from current url
             */
            setFacetsFromParams() {
                this._setCollectionFromRoute();

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