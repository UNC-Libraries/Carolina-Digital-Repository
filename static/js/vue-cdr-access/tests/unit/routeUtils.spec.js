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
            browse_type: gallery
        };
        expect(wrapper.vm.urlParams()).toMatchObject(defaults);
    });

    it("updates url parameters", () => {
        let defaults = {
            rows: 20,
            start: 0,
            sort: 'title,normal',
            browse_type: gallery
        };

        defaults.types = 'Work';
        expect(wrapper.vm.urlParams({types: 'Work'})).toMatchObject(defaults);
    });

    it("formats a url string from an object", () => {
        const defaults = {
            rows: 20,
            start: 0,
            sort: 'title,normal',
            browse_type: gallery
        };
        let formatted = `?rows=20&start=0&sort=title%2Cnormal&browse_type=${gallery}`;
        expect(wrapper.vm.formatParamsString(defaults)).toEqual(formatted);
    });
});