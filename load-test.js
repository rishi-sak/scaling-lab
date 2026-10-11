// fixed test duration


import http from 'k6/http';
import { check } from 'k6';

export const options = {
    vus: 25,
    duration: '30s',
    thresholds: {
        http_req_failed: ['rate<0.01'],
    },
};

export default function () {
    const response = http.get(
        'http://localhost:8080/api/benchmark?iterations=100000'
    );

    check(response, {
        'status is 200': (r) => r.status === 200,
    });
}



//Fixed number of requests

// import http from 'k6/http';
// import { check } from 'k6';
//
// export const options = {
//     vus: 25 ,
//     iterations: 1000,
//     thresholds: {
//         http_req_failed: ['rate<0.01'],
//     },
// };
//
// export default function () {
//     const response = http.get(
//         'http://localhost:8080/api/benchmark?iterations=100000'
//     );
//
//     check(response, {
//         'status is 200': (r) => r.status === 200,
//     });
// }
