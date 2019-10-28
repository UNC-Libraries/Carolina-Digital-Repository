import { createLocalVue, shallowMount } from '@vue/test-utils'
import VueRouter from 'vue-router';
import pagination from '@/components/pagination.vue'
import routeUtils from '@/mixins/routeUtils.js';

const localVue = createLocalVue();
localVue.use(VueRouter);
const router = new VueRouter();
const gallery = 'gallery-display';
let wrapper;

describe('routeUtils', () => {
    beforeEach(() => {
        // Set wrapper using any component that uses routeUtils mixin to avoid test warnings about missing template
        wrapper = shallowMount(pagination, {
            localVue,
            router
        });
    });

    it("sets default url parameters if none are given", () => {
        const defaults = {
            rows: 20,
            start: 0,
            sort: 'title,normal',
            browse_type: gallery,
            works_only: false
        };

        let results = wrapper.vm.urlParams();

        expect(results.rows).toEqual(defaults.rows);
        expect(results.start).toEqual(defaults.start);
        expect(results.sort).toEqual(defaults.sort);
        expect(results.browse_type).toEqual(defaults.browse_type);
        expect(results.works_only).toEqual(defaults.works_only);
    });

    it("updates url parameters", () => {
        let defaults = {
            rows: 20,
            start: 0,
            sort: 'title,normal',
            browse_type: gallery,
            works_only: false
        };

        defaults.types = 'Work';
        let results = wrapper.vm.urlParams({types: 'Work'});

        expect(results.rows).toEqual(defaults.rows);
        expect(results.start).toEqual(defaults.start);
        expect(results.sort).toEqual(defaults.sort);
        expect(results.browse_type).toEqual(defaults.browse_type);
        expect(results.works_only).toEqual(defaults.works_only);
        expect(results.types).toEqual(defaults.types);
    });

    it("formats a url string from an object", () => {
        const defaults = {
            rows: 20,
            start: 0,
            sort: 'title,normal',
            browse_type: gallery,
            works_only: false
        };
        let formatted = `?rows=20&start=0&sort=title%2Cnormal&browse_type=${gallery}&works_only=false`;
        expect(wrapper.vm.formatParamsString(defaults)).toEqual(formatted);
    });

    it("updates work type", () => {
        // Admin units
        expect(wrapper.vm.updateWorkType(true, false).types).toEqual('Collection');
        expect(wrapper.vm.updateWorkType(true, true).types).toEqual('Collection');

        // Other work types gallery view
        wrapper.vm.$router.currentRoute.query.browse_type = 'gallery-display';
        expect(wrapper.vm.updateWorkType(false, false).types).toEqual('Work');
        expect(wrapper.vm.updateWorkType(false, true).types).toEqual('Work');

        // Other work types list view
        wrapper.vm.$router.currentRoute.query.browse_type = 'list-display';
        expect(wrapper.vm.updateWorkType(false, false).types).toEqual('Work,Folder');
        expect(wrapper.vm.updateWorkType(false, true).types).toEqual('Work');
    });

    it("coerces works only value to a boolean from a string", () => {
        expect(wrapper.vm.coerceWorksOnly(true)).toEqual(true);
        expect(wrapper.vm.coerceWorksOnly('true')).toEqual(true);
        expect(wrapper.vm.coerceWorksOnly(false)).toEqual(false);
        expect(wrapper.vm.coerceWorksOnly('false')).toEqual(false);
    });
});