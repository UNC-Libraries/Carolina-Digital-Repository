import { createLocalVue, shallowMount } from '@vue/test-utils';
import VueRouter from 'vue-router';
import pagination from '@/components/pagination.vue';

const localVue = createLocalVue();
localVue.use(VueRouter);
const router = new VueRouter({
    routes: [
        {
            path: '/record/uuid1234',
            name: 'browseDisplay'
        }
    ]
});

let wrapper;

describe('pagination.vue', () => {
    beforeEach(() => {
        wrapper = shallowMount(pagination, {
            localVue,
            router,
            propsData: {
                numberOfRecords: 199,
                pageBaseUrl: 'https://dcr.lib.unc.edu'
            }
        });

        wrapper.setData({
            pageLimit: 5,
            pageOffset: 2,
            startRecord: 1,
            totalPageCount: 1
        });
    });

    it("calculates the total number of pages", () => {
        expect(wrapper.vm.totalPageCount).toEqual(10);
    });

    it("calculates the pages to display", () => {
        expect(wrapper.vm.currentPageList).toEqual([1, 2, 3, 4, 5]);
    });

    it("displays a list of pages if the user is on the first page and there are <= pages than the page limit", () => {
        wrapper.setProps({ numberOfRecords: 24 });
        expect(wrapper.findAll('.page-number').length).toEqual(2);
    });

    it("displays a list of pages if the user is on the first page and there are more pages than the page limit", () => {
        expect(wrapper.findAll('.page-number').length).toEqual(6);
    });

    it("updates the page when a page is selected", () => {
        wrapper.findAll('.page-number').at(3).trigger('click');
        expect(wrapper.vm.currentPage).toEqual(4);
        expect(wrapper.vm.currentPageList).toEqual([2, 3, 4, 5, 6]);
    });

    it("updates the start record when a page is selected", () => {
        wrapper.findAll('.page-number').at(1).trigger('click');
        expect(wrapper.vm.$router.currentRoute.query.start).toEqual(20);
    });
});